package com.juniperphoton.myersplash.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.juniperphoton.myersplash.App
import com.juniperphoton.myersplash.R
import com.juniperphoton.myersplash.api.CloudService
import com.juniperphoton.myersplash.db.AppDatabase
import com.juniperphoton.myersplash.extension.notifyFileUpdated
import com.juniperphoton.myersplash.extension.writeToFile
import com.juniperphoton.myersplash.utils.*
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class DownloadService : Service(), CoroutineScope by CoroutineScope(Dispatchers.IO) {
    companion object {
        private const val TAG = "DownloadService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // A map storing download url to downloading disposable object
    private val downloadUrlToJobMap = HashMap<String, Job>()

    private val dao = AppDatabase.instance.downloadItemDao()

    @SuppressLint("CheckResult")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Pasteur.info(TAG, "on start command")
        intent?.let {
            launch {
                onHandleIntent(it)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Pasteur.w(TAG, "on destroy, cancel all, size: ${downloadUrlToJobMap.size}")
        cancel()
        super.onDestroy()
    }

    private suspend fun checkStatus() {
        Pasteur.info(TAG, "checking status, thread: ${Thread.currentThread()}")
        dao.markAllFailed()
    }

    private suspend fun onHandleIntent(intent: Intent) {
        if (intent.getBooleanExtra(Params.CHECK_STATUS, false)) {
            checkStatus()
            if (downloadUrlToJobMap.isEmpty()) {
                Pasteur.w(TAG, "about to stop self")
                stopSelf()
            }
            return
        }

        val cancelJob = intent.getBooleanExtra(Params.CANCELED_KEY, false)
        val downloadUrl = intent.getStringExtra(Params.URL_KEY)
        val fileName = intent.getStringExtra(Params.NAME_KEY)
        val previewUrl = intent.getStringExtra(Params.PREVIEW_URI)
        val isUnsplash = intent.getBooleanExtra(Params.IS_UNSPLASH_WALLPAPER, true)
        if (!isUnsplash) {
            Toaster.sendShortToast(R.string.downloading)
        }

        val previewUri: Uri? = if (previewUrl.isNullOrEmpty()) null else {
            Uri.parse(previewUrl)
        }

        if (cancelJob) {
            cancelJob(downloadUrl)
        } else {
            Pasteur.d(TAG, "on handle intent progress")
            downloadImage(downloadUrl, fileName, previewUri, isUnsplash)
        }
    }

    private suspend fun cancelJob(url: String) {
        Pasteur.d(TAG, "on handle intent cancelled, thread: ${Thread.currentThread()}")
        downloadUrlToJobMap[url]?.let { job ->
            job.cancelAndJoin()
            downloadUrlToJobMap.remove(url)
            Pasteur.info(TAG, "job cancelled")
            NotificationUtils.cancelNotification(Uri.parse(url))
            Toaster.sendShortToast(getString(R.string.cancelled_download))
        }
    }

    private fun downloadImage(url: String, fileName: String,
                              previewUri: Uri?, isUnsplash: Boolean) {
        val job = launch(context = CoroutineExceptionHandler { _, e ->
            Pasteur.e(TAG, "CoroutineExceptionHandler error $e, url $url")
            onError(url, fileName, null, true)
        }) {
            try {
                val file = DownloadUtils.getFileToSave(fileName)
                val responseBody = CloudService.downloadPhoto(url)

                Pasteur.d(TAG, "outputFile download onNext, " +
                        "size=${responseBody.contentLength()}")

                responseBody.writeToFile(file!!.path) { p ->
                    Pasteur.i(TAG, "dao setting progress: $p")
                    dao.setProgress(url, p)
                }
                onSuccess(url, file, previewUri, isUnsplash)
                Pasteur.d(TAG, getString(R.string.completed))
            } catch (e: CancellationException) {
                // CancellationException will be ignored by CoroutineExceptionHandler,
                // thus we handle it here.
                Pasteur.e(TAG, "CancellationException error $e, url $url")
                onError(url, fileName, null, false)
            }
        }
        downloadUrlToJobMap[url] = job
    }

    private fun onSuccess(url: String, file: File, previewUri: Uri?, isUnsplash: Boolean) {
        Pasteur.d(TAG, "output file:" + file.absolutePath)

        val newFile = File("${file.path.replace(" ", "")}.jpg")
        file.renameTo(newFile)

        Pasteur.d(TAG, "renamed file:" + newFile.absolutePath)
        newFile.notifyFileUpdated(App.instance)

        dao.setSuccess(url, newFile.path)

        NotificationUtils.showCompleteNotification(Uri.parse(url), previewUri,
                if (isUnsplash) null else newFile.absolutePath)
    }

    private fun onError(url: String, fileName: String, previewUri: Uri?, showNotification: Boolean) {
        if (showNotification) {
            NotificationUtils.showErrorNotification(Uri.parse(url), fileName, url, previewUri)
        }
        dao.setFailed(url)
    }
}
