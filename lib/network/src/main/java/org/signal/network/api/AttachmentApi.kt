/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.network.api

import kotlinx.coroutines.runBlocking
import org.signal.core.util.logging.Log
import org.signal.libsignal.net.AuthMessagesService
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.net.UploadTooLargeException
import org.signal.network.NetworkResult
import org.signal.network.exceptions.PushNetworkException
import org.signal.network.websocket.WebSocketRequestMessage
import org.signal.network.websocket.get
import org.whispersystems.signalservice.api.crypto.AttachmentCipherStreamUtil
import org.whispersystems.signalservice.api.fromWebSocketRequest
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream
import org.whispersystems.signalservice.api.websocket.SignalWebSocket
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream
import org.whispersystems.signalservice.internal.push.AttachmentUploadForm
import org.whispersystems.signalservice.internal.push.PushAttachmentData
import org.whispersystems.signalservice.internal.push.PushServiceSocket
import org.whispersystems.signalservice.internal.push.http.AttachmentCipherOutputStreamFactory
import org.whispersystems.signalservice.internal.push.http.ResumableUploadSpec
import java.io.InputStream
import kotlin.jvm.optionals.getOrNull

/**
 * Class to interact with various attachment-related endpoints.
 */
class AttachmentApi(
  private val authWebSocket: SignalWebSocket.AuthenticatedWebSocket,
  private val pushServiceSocket: PushServiceSocket
) {

  companion object {
    private val TAG: String = Log.tag(AttachmentApi::class)
  }

  /**
   * Gets a v4 attachment upload form, which provides the necessary information to upload an attachment.
   *
   * Prefers libsignal chat connection; falls back to OkHttp websocket GET
   * `/v4/attachments/form/upload` for self-hosted builds that cannot use LibSignalChatConnection.
   */
  fun getAttachmentV4UploadForm(uploadSizeBytes: Long): RequestResult<AttachmentUploadForm, UploadTooLargeException> {
    return runBlocking {
      when (
        val result = authWebSocket.runCatchingWithChatConnection { chatConnection ->
          AuthMessagesService(chatConnection).getUploadForm(uploadSizeBytes)
        }
      ) {
        is RequestResult.Success -> RequestResult.Success(
          AttachmentUploadForm(
            cdn = result.result.cdn,
            key = result.result.key,
            headers = result.result.headers,
            signedUploadLocation = result.result.signedUploadUrl.toString()
          )
        )
        is RequestResult.NonSuccess -> RequestResult.NonSuccess(result.error)
        is RequestResult.RetryableNetworkError -> RequestResult.RetryableNetworkError(result.networkError, result.retryAfter)
        is RequestResult.ApplicationError -> {
          if (result.cause is UnsupportedOperationException) {
            Log.i(TAG, "LibSignal chat connection unavailable; fetching attachment form over websocket")
            getAttachmentV4UploadFormOverWebSocket(uploadSizeBytes)
          } else {
            RequestResult.ApplicationError(result.cause)
          }
        }
      }
    }
  }

  /**
   * GET /v4/attachments/form/upload?uploadLength=
   * - 200: Success
   * - 413: Upload too large
   * - 429: Rate limited
   */
  private fun getAttachmentV4UploadFormOverWebSocket(uploadSizeBytes: Long): RequestResult<AttachmentUploadForm, UploadTooLargeException> {
    val request = WebSocketRequestMessage.get("/v4/attachments/form/upload?uploadLength=$uploadSizeBytes")
    return when (val networkResult = NetworkResult.fromWebSocketRequest(authWebSocket, request, AttachmentUploadForm::class)) {
      is NetworkResult.Success -> RequestResult.Success(networkResult.result)
      is NetworkResult.StatusCodeError -> {
        Log.w(TAG, "Attachment form websocket failed: status=${networkResult.code} body=${networkResult.stringBody}")
        when (networkResult.code) {
          413 -> RequestResult.NonSuccess(
            UploadTooLargeException(
              "uploadLength=$uploadSizeBytes exceeds server maximum (HTTP 413): ${networkResult.stringBody}"
            )
          )
          429 -> RequestResult.RetryableNetworkError(
            PushNetworkException("Rate limited fetching attachment upload form"),
            networkResult.retryAfter()?.inWholeSeconds?.let { java.time.Duration.ofSeconds(it) }
          )
          else -> RequestResult.ApplicationError(networkResult.exception)
        }
      }
      is NetworkResult.NetworkError -> RequestResult.RetryableNetworkError(networkResult.exception)
      is NetworkResult.ApplicationError -> RequestResult.ApplicationError(networkResult.throwable)
    }
  }

  /**
   * Uploads an encrypted attachment, automatically choosing the best upload strategy based on CDN version.
   * For CDN3, uses TUS "Creation With Upload" (single POST). For other CDNs, falls back to the legacy
   * resumable upload flow (POST create + HEAD + PATCH).
   *
   * If [existingSpec] is provided, the upload resumes using the existing resumable upload URL (HEAD+PATCH)
   * and [form] is not required.
   * Otherwise, [form] is required, a new upload is initiated, and [onSpecCreated] is called with the
   * [ResumableUploadSpec] before the upload begins, allowing callers to persist it for crash recovery.
   */
  fun uploadAttachmentV4(
    form: AttachmentUploadForm? = null,
    key: ByteArray,
    iv: ByteArray,
    checksumSha256: String?,
    attachmentStream: SignalServiceAttachmentStream,
    existingSpec: ResumableUploadSpec? = null,
    onSpecCreated: ((ResumableUploadSpec) -> Unit)? = null
  ): NetworkResult<AttachmentUploadResult> {
    return NetworkResult.fromFetch {
      require(existingSpec != null || form != null) { "Either existingSpec or form must be provided" }

      val paddedLength = PaddingInputStream.getPaddedSize(attachmentStream.length)
      val dataStream: InputStream = PaddingInputStream(attachmentStream.inputStream, attachmentStream.length)
      val ciphertextLength = AttachmentCipherStreamUtil.getCiphertextLength(paddedLength)

      val effectiveKey = existingSpec?.attachmentKey ?: key
      val effectiveIv = existingSpec?.attachmentIv ?: iv

      val attachmentData = PushAttachmentData(
        contentType = attachmentStream.contentType,
        data = dataStream,
        dataSize = ciphertextLength,
        incremental = attachmentStream.isFaststart,
        outputStreamFactory = AttachmentCipherOutputStreamFactory(effectiveKey, effectiveIv),
        listener = attachmentStream.listener,
        cancelationSignal = attachmentStream.cancelationSignal,
        resumableUploadSpec = existingSpec
      )

      val digestInfo = if (existingSpec != null) {
        Log.i(TAG, "Resuming upload via HEAD+PATCH")
        pushServiceSocket.uploadAttachment(attachmentData)
      } else if (form!!.cdn == 3) {
        Log.i(TAG, "Fresh upload via creation-with-upload (CDN3)")

        val spec = ResumableUploadSpec(
          attachmentKey = key,
          attachmentIv = iv,
          cdnKey = form.key,
          cdnNumber = form.cdn,
          resumeLocation = form.signedUploadLocation + "/" + form.key,
          expirationTimestamp = System.currentTimeMillis() + PushServiceSocket.CDN2_RESUMABLE_LINK_LIFETIME_MILLIS,
          headers = form.headers
        )
        onSpecCreated?.invoke(spec)

        pushServiceSocket.createAndUploadToCdn3(form, checksumSha256, attachmentData)
      } else {
        Log.i(TAG, "Fresh upload via legacy flow (CDN${form.cdn})")
        val resumeUrl = pushServiceSocket.getResumableUploadUrl(form, checksumSha256)
        val spec = ResumableUploadSpec(
          attachmentKey = key,
          attachmentIv = iv,
          cdnKey = form.key,
          cdnNumber = form.cdn,
          resumeLocation = resumeUrl,
          expirationTimestamp = System.currentTimeMillis() + PushServiceSocket.CDN2_RESUMABLE_LINK_LIFETIME_MILLIS,
          headers = form.headers
        )
        onSpecCreated?.invoke(spec)

        pushServiceSocket.uploadAttachment(attachmentData.copy(resumableUploadSpec = spec))
      }

      val cdnKey = existingSpec?.cdnKey ?: form!!.key
      val cdnNumber = existingSpec?.cdnNumber ?: form!!.cdn

      AttachmentUploadResult(
        remoteId = SignalServiceAttachmentRemoteId.V4(cdnKey),
        cdnNumber = cdnNumber,
        key = key,
        digest = digestInfo.digest,
        incrementalDigest = digestInfo.incrementalDigest,
        incrementalDigestChunkSize = digestInfo.incrementalMacChunkSize,
        uploadTimestamp = attachmentStream.uploadTimestamp,
        dataSize = attachmentStream.length,
        blurHash = attachmentStream.blurHash.getOrNull()
      )
    }
  }
}
