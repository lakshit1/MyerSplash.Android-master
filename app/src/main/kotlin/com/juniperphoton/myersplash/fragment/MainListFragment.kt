package com.juniperphoton.myersplash.fragment

import android.app.Activity
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import butterknife.BindView
import butterknife.ButterKnife
import com.juniperphoton.myersplash.R
import com.juniperphoton.myersplash.adapter.PhotoAdapter
import com.juniperphoton.myersplash.contract.MainContract
import com.juniperphoton.myersplash.event.RefreshUIEvent
import com.juniperphoton.myersplash.event.ScrollToTopEvent
import com.juniperphoton.myersplash.extension.usingWifi
import com.juniperphoton.myersplash.model.UnsplashImage
import com.juniperphoton.myersplash.utils.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

typealias Action = (() -> Unit)
typealias OnClickPhotoItemListener = ((rectF: RectF, unsplashImage: UnsplashImage, itemView: View) -> Unit)

@Suppress("unused", "unused_parameter")
class MainListFragment : BasePresenterFragment<MainContract.MainPresenter>(), MainContract.MainView {
    companion object {
        private const val TAG = "MainListFragment"
        private const val SCROLL_DETECTION_FACTOR_PX = 20
        private const val SCROLL_START_POSITION = 5
    }

    @BindView(R.id.content_activity_rv)
    lateinit var contentRecyclerView: RecyclerView

    @BindView(R.id.content_activity_srl)
    lateinit var refreshLayout: SwipeRefreshLayout

    @BindView(R.id.no_item_layout)
    lateinit var noItemLayout: LinearLayout

    @BindView(R.id.no_item_retry_btn)
    lateinit var retryBtn: View

    private var adapter: PhotoAdapter? = null
    private var loadMoreListener: LoadMoreListener? = null

    override val isBusyRefreshing: Boolean
        get() = refreshLayout.isRefreshing

    var onScrollHide: Action? = null
    var onScrollShow: Action? = null
    var onClickPhotoItem: OnClickPhotoItemListener? = null

    private var loadedData: Boolean = false
    private var visible: Boolean = false
    private var viewLoaded: Boolean = false

    private var query: String? = null

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Pasteur.info(TAG, "onCreateView $activity")

        val view = LayoutInflater.from(activity!!).inflate(R.layout.fragment_list, container, false)

        ButterKnife.bind(this, view)

        viewLoaded = true
        initView()

        presenter?.start()

        if (visible && !loadedData) {
            presenter?.refresh()
            loadedData = true
        }

        return view
    }

    override fun onDestroy() {
        Pasteur.info(TAG, "onDestroy $activity")
        presenter?.stop()
        super.onDestroy()
    }

    override fun showToast(text: String) {
        Toaster.sendShortToast(text)
    }

    override fun showToast(textId: Int) {
        Toaster.sendShortToast(textId)
    }

    override fun search(query: String) {
        presenter?.search(query)
    }

    override fun refreshList(images: MutableList<UnsplashImage>, next: Int) {
        AnalysisHelper.logRefreshList()

        if (next == 1 || adapter == null) {
            displayListDataInternal(images)
        } else {
            adapter?.setLoadMoreData(images)
        }
        if (adapter == null) {
            updateNoItemVisibility(true)
        } else if (images.size == 0 && adapter!!.itemCount == 0) {
            updateNoItemVisibility(true)
        } else {
            updateNoItemVisibility(false)
        }
    }

    override fun updateNoItemVisibility() {
        if (adapter != null && adapter!!.itemCount > 0) {
            updateNoItemVisibility(false)
        } else {
            updateNoItemVisibility(true)
        }
    }

    override fun setRefreshing(refreshing: Boolean) {
        refreshLayout.isRefreshing = refreshing
    }

    override fun scrollToTop() {
        val lm = contentRecyclerView.layoutManager as LinearLayoutManager
        val pos = lm.findFirstCompletelyVisibleItemPosition()
        if (pos > SCROLL_START_POSITION) {
            contentRecyclerView.scrollToPosition(SCROLL_START_POSITION)
        }
        contentRecyclerView.smoothScrollToPosition(0)
    }

    override fun registerEvent() {
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun unregisterEvent() {
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
    }

    override fun clearData() {
        adapter?.clear()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: ScrollToTopEvent) {
        presenter?.onReceivedScrollToTopEvent(event)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: RefreshUIEvent) {
        adapter?.notifyDataSetChanged()
    }

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        visible = isVisibleToUser

        if (visible && !loadedData && viewLoaded) {
            presenter?.refresh()
            loadedData = true
        }

        if (visible) {
            registerEvent()
        } else {
            unregisterEvent()
        }
    }

    private fun requestRefresh() {
        if (refreshLayout.isRefreshing) {
            return
        }
        presenter?.refresh()
    }

    private fun initView() {
        refreshLayout.setOnRefreshListener {
            presenter?.refresh()
        }
        contentRecyclerView.layoutManager = LinearLayoutManager(activity, RecyclerView.VERTICAL, false)
        contentRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(list: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(list, dx, dy)
                if (dy > SCROLL_DETECTION_FACTOR_PX) {
                    onScrollHide?.invoke()
                } else if (dy < -SCROLL_DETECTION_FACTOR_PX) {
                    onScrollShow?.invoke()
                }
            }
        })
        retryBtn.setOnClickListener {
            updateNoItemVisibility(false)
            presenter?.reloadList()
        }
    }

    private fun displayListDataInternal(unsplashImages: MutableList<UnsplashImage>) {
        if (adapter?.firstImage != null) {
            if (unsplashImages.isNotEmpty() && adapter?.firstImage?.id == unsplashImages[0].id) {
                return
            }
        }

        val context = activity ?: return

        adapter = PhotoAdapter(unsplashImages, context)
        adapter?.onClickQuickDownload = { image ->
            AnalysisHelper.logClickDownloadInList()
            download(image)
        }
        adapter?.onClickPhoto = onClickPhotoItem
        contentRecyclerView.adapter = adapter

        loadMoreListener = LoadMoreListener {
            presenter?.loadMore()
        }
        loadMoreListener!!.attach(contentRecyclerView)
    }

    private fun download(image: UnsplashImage) {
        val context = context ?: return

        if (!PermissionUtils.check(context as Activity)) {
            Toaster.sendShortToast(context.getString(R.string.no_permission))
            return
        }

        val warn = LocalSettingHelper.getBoolean(context,
                context.getString(R.string.preference_key_download_via_metered_network), true)

        if (warn && !context.usingWifi()) {
            val builder = buildMeteredWarningDialog(context) {
                DownloadUtils.download(context, image)
            }
            builder.create().show()
        } else {
            DownloadUtils.download(context, image)
        }
    }

    private fun updateNoItemVisibility(show: Boolean) {
        noItemLayout.visibility = if (show) View.VISIBLE else View.GONE
    }
}
