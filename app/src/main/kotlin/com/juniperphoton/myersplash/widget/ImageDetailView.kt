package com.juniperphoton.myersplash.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.ViewModelProviders
import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnClick
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.juniperphoton.flipperlayout.FlipperLayout
import com.juniperphoton.myersplash.R
import com.juniperphoton.myersplash.activity.EditActivity
import com.juniperphoton.myersplash.extension.*
import com.juniperphoton.myersplash.fragment.Action
import com.juniperphoton.myersplash.model.DownloadItem
import com.juniperphoton.myersplash.model.UnsplashImage
import com.juniperphoton.myersplash.utils.*
import com.juniperphoton.myersplash.view.ImageDetailViewContract
import com.juniperphoton.myersplash.viewmodel.ImageDetailViewModel
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

@Suppress("unused")
class ImageDetailView(context: Context, attrs: AttributeSet
) : FrameLayout(context, attrs), ImageDetailViewContract {
    companion object {
        private const val TAG = "ImageDetailView"
        private const val RESULT_CODE = 10000

        private const val DOWNLOAD_FLIPPER_LAYOUT_STATUS_DOWNLOAD = 0
        private const val DOWNLOAD_FLIPPER_LAYOUT_STATUS_DOWNLOADING = 1
        private const val DOWNLOAD_FLIPPER_LAYOUT_STATUS_DOWNLOAD_OK = 2

        private const val RESET_THRESHOLD = 150
        private const val MOVE_THRESHOLD = 10

        private const val ANIMATION_DURATION_FAST_MILLIS = 300L
        private const val ANIMATION_DURATION_SLOW_MILLIS = 400L
        private const val ANIMATION_DURATION_VERY_SLOW_MILLIS = 500L

        private const val URL_COPIED_DELAY_MILLIS = 2000L
    }

    private var listPositionY = 0f

    private var clickedView: View? = null

    private var scope: CoroutineScope? = null

    /**
     * Invoked when the display animation is started.
     */
    var onShowing: Action? = null

    /**
     * Invoke when the view is fully displayed.
     */
    var onShown: Action? = null

    /**
     * Invoked when the view is about to hide.
     */
    var onHiding: Action? = null

    /**
     * Invoked when the view is invisible to user.
     */
    var onHidden: Action? = null

    @BindView(R.id.detail_root_sv)
    lateinit var detailRootScrollView: ViewGroup

    @BindView(R.id.detail_hero_view)
    lateinit var heroView: SimpleDraweeView

    @BindView(R.id.detail_backgrd_rl)
    lateinit var detailInfoRootLayout: ViewGroup

    @BindView(R.id.detail_img_rl)
    lateinit var detailImgRL: ViewGroup

    @BindView(R.id.detail_name_tv)
    lateinit var nameTextView: TextView

    @BindView(R.id.detail_name_line)
    lateinit var lineView: View

    @BindView(R.id.detail_photo_by_tv)
    lateinit var photoByTextView: TextView

    @BindView(R.id.detail_download_fab)
    lateinit var downloadFAB: FloatingActionButton

    @BindView(R.id.detail_cancel_download_fab)
    lateinit var cancelDownloadFAB: FloatingActionButton

    @BindView(R.id.detail_share_fab)
    lateinit var shareFAB: FloatingActionButton

    @BindView(R.id.copy_url_tv)
    lateinit var copyUrlTextView: TextView

    @BindView(R.id.copied_url_tv)
    lateinit var copiedUrlTextView: TextView

    @BindView(R.id.copy_url_fl)
    lateinit var copyLayout: FrameLayout

    @BindView(R.id.copied_url_fl)
    lateinit var copiedLayout: FrameLayout

    @BindView(R.id.copy_url_flipper_layout)
    lateinit var copyUrlFlipperLayout: FlipperLayout

    @BindView(R.id.download_flipper_layout)
    lateinit var downloadFlipperLayout: FlipperLayout

    @BindView(R.id.detail_progress_ring)
    lateinit var progressView: RingProgressView

    @BindView(R.id.detail_set_as_fab)
    lateinit var setAsFAB: FloatingActionButton

    private lateinit var viewModel: ImageDetailViewModel

    private val shareButtonHideOffset: Int
        get() = resources.getDimensionPixelSize(R.dimen.share_btn_margin_right_hide)

    private val downloadFlipperLayoutHideOffset: Int
        get() = resources.getDimensionPixelSize(R.dimen.download_btn_margin_right_hide)

    private var animating: Boolean = false
    private var copied: Boolean = false

    init {
        LayoutInflater.from(context).inflate(R.layout.detail_content, this, true)
        ButterKnife.bind(this, this)

        initDetailViews()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initDetailViews() {
        viewModel = ViewModelProviders.of(context as FragmentActivity).get(ImageDetailViewModel::class.java)
        viewModel.viewContract = this

        detailRootScrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                tryHide()
            }
            true
        }

        detailRootScrollView.visibility = View.INVISIBLE

        detailInfoRootLayout.translationY = (-resources.getDimensionPixelSize(R.dimen.img_detail_info_height)).toFloat()
        downloadFlipperLayout.translationX = resources.getDimensionPixelSize(R.dimen.download_btn_margin_right_hide).toFloat()
        shareFAB.translationX = resources.getDimensionPixelSize(R.dimen.share_btn_margin_right_hide).toFloat()

        ValueAnimator.ofFloat(0f, 360f).apply {
            addUpdateListener { animation -> progressView.rotation = animation.animatedValue as Float }
            interpolator = LinearInterpolator()
            duration = 1200
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        heroView.setOnTouchListener { _, e ->
            if (animating) {
                return@setOnTouchListener false
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY

                    startX = detailImgRL.translationX
                    startY = detailImgRL.translationY

                    pointerDown = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pointerDown) {
                        return@setOnTouchListener false
                    }
                    if (downX == 0f || downY == 0f) {
                        downX = e.rawX
                        downY = e.rawY

                        startX = detailImgRL.translationX
                        startY = detailImgRL.translationY
                    }

                    val dx = e.rawX - downX
                    val dy = e.rawY - downY

                    if (Math.abs(dx) >= MOVE_THRESHOLD || Math.abs(dy) >= MOVE_THRESHOLD) {
                        toggleFadeAnimation(false)
                    }

                    detailImgRL.translationX = startX + dx
                    detailImgRL.translationY = startY + dy
                }
                MotionEvent.ACTION_UP -> {
                    if (!pointerDown) {
                        return@setOnTouchListener false
                    }

                    if (Math.abs(e.rawY - downY) >= RESET_THRESHOLD || Math.abs(e.rawX - downX) >= RESET_THRESHOLD) {
                        tryHide()
                    } else {
                        detailImgRL.animate().translationX(startX).translationY(startY).setDuration(ANIMATION_DURATION_FAST_MILLIS).start()
                        toggleFadeAnimation(true)
                    }

                    pointerDown = false
                }
            }
            true
        }
    }

    private var downX: Float = 0f
    private var downY: Float = 0f

    private var startX: Float = 0f
    private var startY: Float = 0f

    private var pointerDown: Boolean = false

    private fun toggleFadeAnimation(show: Boolean) {
        if (show) {
            if (detailInfoRootLayout.alpha == 1f) {
                return
            }
        } else if (detailInfoRootLayout.alpha == 0f) {
            return
        }

        val valueAnimator = ValueAnimator.ofFloat(if (show) 1f else 0f)
        valueAnimator.addUpdateListener {
            detailInfoRootLayout.alpha = it.animatedValue as Float
            shareFAB.alpha = it.animatedValue as Float
            downloadFlipperLayout.alpha = it.animatedValue as Float
        }
        valueAnimator.setDuration(ANIMATION_DURATION_FAST_MILLIS).start()
    }

    private fun resetStatus() {
        shareFAB.alpha = 1f
        detailInfoRootLayout.alpha = 1f
        downloadFlipperLayout.alpha = 1f

        shareFAB.translationX = shareButtonHideOffset.toFloat()
        downloadFlipperLayout.translationX = downloadFlipperLayoutHideOffset.toFloat()
    }

    private fun toggleHeroViewAnimation(startY: Float, endY: Float, show: Boolean) {
        if (!show) {
            downloadFlipperLayout.updateIndex(DOWNLOAD_FLIPPER_LAYOUT_STATUS_DOWNLOAD)
        } else {
            detailImgRL.translationX = 0f
        }

        val startX = detailImgRL.translationX

        ValueAnimator.ofFloat(startY, endY).apply {
            duration = ANIMATION_DURATION_FAST_MILLIS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                detailImgRL.translationX = startX * (1 - it.animatedFraction)
                detailImgRL.translationY = it.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (!show && clickedView != null) {
                        clickedView!!.visibility = View.VISIBLE
                        toggleMaskAnimation(false)
                        clickedView = null
                        animating = false
                        quickReset()
                    } else {
                        toggleDetailRLAnimation(show = true, oneshot = false)
                        toggleDownloadFlipperLayoutAnimation(show = true, oneshot = false)
                        toggleShareBtnAnimation(show = true, oneshot = false)
                    }
                }
            })
            start()
        }
    }

    private fun checkDownloadStatus(item: DownloadItem): Boolean {
        val file = File(item.filePath)
        return file.exists() && file.canRead()
    }

    private val targetY: Float
        get() {
            val decorView = (context as Activity).window.decorView
            val height = decorView.height
            val width = decorView.width
            val detailHeight = (width / (3 / 2f)).toInt() +
                    context.resources.getDimensionPixelSize(R.dimen.img_detail_info_height)
            return (height - detailHeight) / 2f
        }

    private fun toggleDetailRLAnimation(show: Boolean, oneshot: Boolean) {
        val startY = if (show) -resources.getDimensionPixelSize(R.dimen.img_detail_info_height) else 0
        val endY = if (show) 0 else -resources.getDimensionPixelSize(R.dimen.img_detail_info_height)

        detailInfoRootLayout.translationY = startY.toFloat()

        ValueAnimator().apply {
            setFloatValues(startY.toFloat(), endY.toFloat())
            duration = if (oneshot) 0 else ANIMATION_DURATION_SLOW_MILLIS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { animation ->
                detailInfoRootLayout.translationY = animation.animatedValue as Float
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(a: Animator) {
                    animating = true
                }

                override fun onAnimationEnd(a: Animator) {
                    if (!show) {
                        toggleHeroViewAnimation(detailImgRL.translationY, listPositionY, false)
                    } else {
                        animating = false
                    }
                }
            })
            start()
        }
    }

    private fun toggleDownloadFlipperLayoutAnimation(show: Boolean, oneshot: Boolean) {
        val hideX = downloadFlipperLayoutHideOffset

        val start = if (show) hideX else 0
        val end = if (show) 0 else hideX

        ValueAnimator().apply {
            setFloatValues(start.toFloat(), end.toFloat())
            duration = if (oneshot) 0 else ANIMATION_DURATION_VERY_SLOW_MILLIS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation -> downloadFlipperLayout.translationX = animation.animatedValue as Float }
            start()
        }
    }

    private fun toggleShareBtnAnimation(show: Boolean, oneshot: Boolean) {
        val hideX = shareButtonHideOffset

        val start = if (show) hideX else 0
        val end = if (show) 0 else hideX

        ValueAnimator().apply {
            setFloatValues(start.toFloat(), end.toFloat())
            duration = if (oneshot) 0 else ANIMATION_DURATION_VERY_SLOW_MILLIS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation -> shareFAB.translationX = animation.animatedValue as Float }
            start()
        }
    }

    private fun toggleMaskAnimation(show: Boolean) {
        val animator = ValueAnimator.ofArgb(if (show) Color.TRANSPARENT else ContextCompat.getColor(context, R.color.MaskColor),
                if (show) ContextCompat.getColor(context, R.color.MaskColor) else Color.TRANSPARENT)
        animator.duration = ANIMATION_DURATION_FAST_MILLIS
        animator.addUpdateListener { animation -> detailRootScrollView.background = ColorDrawable(animation.animatedValue as Int) }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(a: Animator) {
                if (show) {
                    onShowing?.invoke()
                } else {
                    onHiding?.invoke()
                }
            }

            override fun onAnimationEnd(a: Animator) {
                if (show) {
                    onShown?.invoke()
                } else {
                    resetStatus()
                    detailRootScrollView.visibility = View.INVISIBLE
                    onHidden?.invoke()
                }
            }
        })
        animator.start()
    }

    private fun hideDetailPanel() {
        if (animating) return

        val oneshot = detailInfoRootLayout.alpha == 0f
        toggleDetailRLAnimation(false, oneshot)
        toggleDownloadFlipperLayoutAnimation(false, oneshot)
        toggleShareBtnAnimation(false, oneshot)
    }

    private fun extractThemeColor(image: UnsplashImage) = scope?.launch {
        val color = image.extractThemeColor()
        if (color != Int.MIN_VALUE) {
            updateThemeColor(color)
        } else {
            updateThemeColor(Color.BLACK)
        }
    }

    private fun updateThemeColor(themeColor: Int) {
        detailInfoRootLayout.background = ColorDrawable(themeColor)
        // change the color
        if (!themeColor.isLightColor()) {
            copyUrlTextView.setTextColor(Color.BLACK)
            val backColor = Color.argb(200, Color.red(Color.WHITE),
                    Color.green(Color.WHITE), Color.blue(Color.WHITE))
            copyLayout.setBackgroundColor(backColor)

            nameTextView.setTextColor(Color.WHITE)
            lineView.background = ColorDrawable(Color.WHITE)
            photoByTextView.setTextColor(Color.WHITE)
        } else {
            copyUrlTextView.setTextColor(Color.WHITE)
            val backColor = Color.argb(200, Color.red(Color.BLACK),
                    Color.green(Color.BLACK), Color.blue(Color.BLACK))
            copyLayout.setBackgroundColor(backColor)

            nameTextView.setTextColor(Color.BLACK)
            lineView.background = ColorDrawable(Color.BLACK)
            photoByTextView.setTextColor(Color.BLACK)
        }
    }

    @OnClick(R.id.detail_name_tv)
    fun onClickName() {
        viewModel.navigateToAuthorPage()
    }

    @OnClick(R.id.copy_url_flipper_layout)
    fun onClickCopy() {
        copyInternal()
    }

    private fun copyInternal() = scope?.launch {
        if (copied) return@launch
        copied = true

        copyUrlFlipperLayout.next()

        AnalysisHelper.logClickCopyUrl()

        viewModel.copyUrlToClipboard()
        delay(URL_COPIED_DELAY_MILLIS)
        copyUrlFlipperLayout.next()
        copied = false
    }

    @OnClick(R.id.detail_share_fab)
    fun onClickShare() {
        viewModel.share()
    }

    @OnClick(R.id.detail_download_fab)
    fun onClickDownload() {
        if (!PermissionUtils.check(context as Activity)) {
            Toaster.sendShortToast(context.getString(R.string.no_permission))
            return
        }

        val warn = LocalSettingHelper.getBoolean(context,
                context.getString(R.string.preference_key_download_via_metered_network), true)

        if (warn && !context.usingWifi()) {
            val builder = buildMeteredWarningDialog(context) {
                viewModel.download()
            }
            builder.create().show()
        } else {
            viewModel.download()
        }
    }

    @OnClick(R.id.detail_cancel_download_fab)
    fun onClickCancelDownload() {
        if (viewModel.cancelDownload()) {
            downloadFlipperLayout.updateIndex(DOWNLOAD_FLIPPER_LAYOUT_STATUS_DOWNLOAD)
        }
    }

    @OnClick(R.id.detail_set_as_fab)
    fun onClickSetAsFAB() {
        viewModel.setAs()
    }

    override fun launchEditActivity(uri: Uri) {
        val intent = Intent(context, EditActivity::class.java)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        context.startActivity(intent)
    }

    override fun navigateToAuthorPage(url: String) {
        val uri = Uri.parse(url)

        val intentBuilder = CustomTabsIntent.Builder()

        intentBuilder.setToolbarColor(ContextCompat.getColor(context, R.color.colorPrimary))
        intentBuilder.setSecondaryToolbarColor(ContextCompat.getColor(context, R.color.colorPrimaryDark))

        intentBuilder.setStartAnimations(context, R.anim.in_from_right, R.anim.out_from_left)
        intentBuilder.setExitAnimations(context, R.anim.in_from_left, R.anim.out_from_right)

        val customTabsIntent = intentBuilder.build()

        customTabsIntent.launchUrl(context, uri)
    }

    override fun launchShare(uri: Uri, text: String) {
        val intent = Intent(Intent.ACTION_SEND)

        intent.apply {
            action = Intent.ACTION_SEND
            type = "image/*"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Share")
            putExtra(Intent.EXTRA_TEXT, text)
        }

        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_title)))
    }

    private var disposable: Disposable? = null

    /**
     * Show detailed image
     * @param rectF         rect of original image position
     * @param unsplashImage clicked image
     * @param itemView      clicked view
     */
    fun show(rectF: RectF, unsplashImage: UnsplashImage, itemView: View) {
        AnalysisHelper.logToggleImageDetails()

        if (clickedView != null) {
            return
        }

        scope = CoroutineScope(Dispatchers.Main)

        viewModel.unsplashImage = unsplashImage

        clickedView = itemView
        clickedView!!.visibility = View.INVISIBLE

        val themeColor = unsplashImage.themeColor

        if (!unsplashImage.isUnsplash) {
            photoByTextView.text = context.getString(R.string.recommended_by)

            extractThemeColor(unsplashImage)
        } else {
            photoByTextView.text = context.getString(R.string.photo_by)
            detailInfoRootLayout.background = ColorDrawable(themeColor)
        }

        updateThemeColor(themeColor)

        nameTextView.text = unsplashImage.userName
        progressView.progress = 5

        heroView.setImageURI(unsplashImage.listUrl)
        detailRootScrollView.visibility = View.VISIBLE

        val heroImagePosition = IntArray(2)
        detailImgRL.getLocationOnScreen(heroImagePosition)

        listPositionY = rectF.top

        disposable = viewModel.associatedDownloadItem
                ?.distinctUntilChanged { prev, current ->
                    prev == current
                }
                ?.delay { item ->
                    return@delay if (item.status == DownloadItem.DOWNLOAD_STATUS_OK && !animating) {
                        Flowable.just(item).delay(FlipperLayout.DEFAULT_DURATION_MILLIS, TimeUnit.MILLISECONDS)
                    } else {
                        Flowable.just(item)
                    }
                }
                ?.observeOn(AndroidSchedulers.mainThread())
                ?.subscribe { item ->
                    updateByItem(item)
                }

        toggleMaskAnimation(true)
        toggleHeroViewAnimation(listPositionY, targetY, true)
    }

    @UiThread
    private fun updateByItem(item: DownloadItem?) {
        Pasteur.info(TAG, "observe on new value: $item")
        when (item?.status) {
            DownloadItem.DOWNLOAD_STATUS_DOWNLOADING -> {
                progressView.progress = item.progress
                downloadFlipperLayout.updateIndex(DOWNLOAD_FLIPPER_LAYOUT_STATUS_DOWNLOADING)
            }
            DownloadItem.DOWNLOAD_STATUS_FAILED -> {
                downloadFlipperLayout.updateIndex(DOWNLOAD_FLIPPER_LAYOUT_STATUS_DOWNLOAD)
            }
            DownloadItem.DOWNLOAD_STATUS_OK -> {
                if (checkDownloadStatus(item)) {
                    val index = DOWNLOAD_FLIPPER_LAYOUT_STATUS_DOWNLOAD_OK
                    downloadFlipperLayout.updateIndex(index)
                }
            }
        }
    }

    private fun quickReset() {
        copyUrlFlipperLayout.updateIndexWithoutAnimation(0)
        downloadFlipperLayout.updateIndexWithoutAnimation(DOWNLOAD_FLIPPER_LAYOUT_STATUS_DOWNLOAD)
        copied = false
    }

    /**
     * Try to hide this view. If this view is fully displayed to user.
     */
    fun tryHide(): Boolean {
        scope?.cancel()
        disposable?.dispose()
        disposable = null
        viewModel.onHide()
        if (detailRootScrollView.visibility == View.VISIBLE) {
            hideDetailPanel()
            return true
        }
        return false
    }
}
