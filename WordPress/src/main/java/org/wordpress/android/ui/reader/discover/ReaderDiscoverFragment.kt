package org.wordpress.android.ui.reader.discover

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.wordpress.android.R
import org.wordpress.android.R.dimen
import org.wordpress.android.WordPress
import org.wordpress.android.databinding.ReaderDiscoverFragmentLayoutBinding
import org.wordpress.android.ui.ActivityLauncher
import org.wordpress.android.ui.RequestCodes
import org.wordpress.android.ui.ViewPagerFragment
import org.wordpress.android.ui.main.SitePickerActivity
import org.wordpress.android.ui.pages.SnackbarMessageHolder
import org.wordpress.android.ui.reader.ReaderActivityLauncher
import org.wordpress.android.ui.reader.ReaderActivityLauncher.OpenUrlType
import org.wordpress.android.ui.reader.ReaderPostWebViewCachingFragment
import org.wordpress.android.ui.reader.discover.ReaderDiscoverViewModel.DiscoverUiState.ContentUiState
import org.wordpress.android.ui.reader.discover.ReaderDiscoverViewModel.DiscoverUiState.EmptyUiState
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.OpenEditorForReblog
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.OpenPost
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.SharePost
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowBlogPreview
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowBookmarkedSavedOnlyLocallyDialog
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowBookmarkedTab
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowNoSitesToReblog
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowPostDetail
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowPostsByTag
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowReaderComments
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowReaderSubs
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowRelatedPostDetails
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowReportPost
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowSitePickerForResult
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowVideoViewer
import org.wordpress.android.ui.reader.usecases.BookmarkPostState.PreLoadPostContent
import org.wordpress.android.ui.reader.utils.ReaderUtilsWrapper
import org.wordpress.android.ui.reader.viewmodels.ReaderViewModel
import org.wordpress.android.ui.utils.UiHelpers
import org.wordpress.android.util.WPSwipeToRefreshHelper
import org.wordpress.android.util.analytics.AnalyticsTrackerWrapper
import org.wordpress.android.util.image.ImageManager
import org.wordpress.android.viewmodel.observeEvent
import org.wordpress.android.widgets.RecyclerItemDecoration
import org.wordpress.android.widgets.WPSnackbar
import javax.inject.Inject

class ReaderDiscoverFragment : ViewPagerFragment(R.layout.reader_discover_fragment_layout) {
    private var bookmarksSavedLocallyDialog: AlertDialog? = null
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var uiHelpers: UiHelpers
    @Inject lateinit var imageManager: ImageManager
    private lateinit var viewModel: ReaderDiscoverViewModel
    @Inject lateinit var analyticsTrackerWrapper: AnalyticsTrackerWrapper
    @Inject lateinit var readerUtilsWrapper: ReaderUtilsWrapper
    private lateinit var parentViewModel: ReaderViewModel

    private var _binding: ReaderDiscoverFragmentLayoutBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (requireActivity().application as WordPress).component().inject(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = ReaderDiscoverFragmentLayoutBinding.bind(view)
        setupViews(binding)
        initViewModel(binding)
    }

    private fun setupViews(binding: ReaderDiscoverFragmentLayoutBinding) = with(binding) {
        recyclerView.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        recyclerView.adapter = ReaderDiscoverAdapter(uiHelpers, imageManager)

        val spacingHorizontal = resources.getDimensionPixelSize(dimen.reader_card_margin)
        val spacingVertical = resources.getDimensionPixelSize(dimen.reader_card_gutters)
        recyclerView.addItemDecoration(RecyclerItemDecoration(spacingHorizontal, spacingVertical, false))

        WPSwipeToRefreshHelper.buildSwipeToRefreshHelper(ptrLayout) {
            viewModel.swipeToRefresh()
        }
    }

    private fun initViewModel(binding: ReaderDiscoverFragmentLayoutBinding) {
        viewModel = ViewModelProvider(this, viewModelFactory).get(ReaderDiscoverViewModel::class.java)
        parentViewModel = ViewModelProvider(requireParentFragment()).get(ReaderViewModel::class.java)

        setupObservers(binding)

        viewModel.start(parentViewModel)
    }

    private fun setupObservers(binding: ReaderDiscoverFragmentLayoutBinding) = with(binding) {
        viewModel.uiState.observe(viewLifecycleOwner) {
            when (it) {
                is ContentUiState -> {
                    (recyclerView.adapter as ReaderDiscoverAdapter).update(it.cards)
                    if (it.scrollToTop) {
                        recyclerView.scrollToPosition(0)
                    }
                }
                is EmptyUiState -> {
                    uiHelpers.setTextOrHide(actionableEmptyView.title, it.titleResId)
                    uiHelpers.setTextOrHide(actionableEmptyView.subtitle, it.subTitleRes)
                    uiHelpers.setImageOrHide(actionableEmptyView.image, it.illustrationResId)
                    uiHelpers.setTextOrHide(actionableEmptyView.button, it.buttonResId)
                    actionableEmptyView.button.setOnClickListener { _ ->
                        it.action.invoke()
                    }
                }
            }
            uiHelpers.updateVisibility(recyclerView, it.contentVisiblity)
            uiHelpers.updateVisibility(progressBar, it.fullscreenProgressVisibility)
            uiHelpers.updateVisibility(progressText, it.fullscreenProgressVisibility)
            uiHelpers.updateVisibility(progressLoadingMore, it.loadMoreProgressVisibility)
            uiHelpers.updateVisibility(actionableEmptyView, it.fullscreenEmptyVisibility)
            ptrLayout.isEnabled = it.swipeToRefreshEnabled
            ptrLayout.isRefreshing = it.reloadProgressVisibility
        }
        viewModel.navigationEvents.observeEvent(viewLifecycleOwner) {
            when (it) {
                is ShowPostDetail -> ReaderActivityLauncher.showReaderPostDetail(
                        context,
                        it.post.blogId,
                        it.post.postId
                )
                is SharePost -> ReaderActivityLauncher.sharePost(context, it.post)
                is OpenPost -> ReaderActivityLauncher.openPost(context, it.post)
                is ShowReaderComments -> ReaderActivityLauncher.showReaderComments(context, it.blogId, it.postId)
                is ShowNoSitesToReblog -> ReaderActivityLauncher.showNoSiteToReblog(activity)
                is ShowSitePickerForResult -> ActivityLauncher
                        .showSitePickerForResult(this@ReaderDiscoverFragment, it.preselectedSite, it.mode)
                is OpenEditorForReblog -> ActivityLauncher
                        .openEditorForReblog(activity, it.site, it.post, it.source)
                is ShowBookmarkedTab -> {
                    ActivityLauncher.viewSavedPostsListInReader(activity)
                }
                is ShowBookmarkedSavedOnlyLocallyDialog -> showBookmarkSavedLocallyDialog(it)
                is ShowPostsByTag -> ReaderActivityLauncher.showReaderTagPreview(context, it.tag)
                is ShowVideoViewer -> ReaderActivityLauncher.showReaderVideoViewer(context, it.videoUrl)
                is ShowBlogPreview -> ReaderActivityLauncher.showReaderBlogOrFeedPreview(
                        context,
                        it.siteId,
                        it.feedId
                )
                is ShowReportPost -> {
                    ReaderActivityLauncher.openUrl(
                            context,
                            readerUtilsWrapper.getReportPostUrl(it.url),
                            OpenUrlType.INTERNAL
                    )
                }
                is ShowReaderSubs -> {
                    ReaderActivityLauncher.showReaderSubs(context)
                }
                is ShowRelatedPostDetails -> Unit // Do Nothing
            }
        }
        viewModel.snackbarEvents.observeEvent(viewLifecycleOwner) {
            it.showSnackbar()
        }
        viewModel.preloadPostEvents.observeEvent(viewLifecycleOwner) {
            it.addWebViewCachingFragment()
        }
    }

    private fun showBookmarkSavedLocallyDialog(bookmarkDialog: ShowBookmarkedSavedOnlyLocallyDialog) {
        if (bookmarksSavedLocallyDialog == null) {
            MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(getString(bookmarkDialog.title))
                    .setMessage(getString(bookmarkDialog.message))
                    .setPositiveButton(getString(bookmarkDialog.buttonLabel)) { _, _ ->
                        bookmarkDialog.okButtonAction.invoke()
                    }
                    .setOnDismissListener {
                        bookmarksSavedLocallyDialog = null
                    }
                    .setCancelable(false)
                    .create()
                    .let {
                        bookmarksSavedLocallyDialog = it
                        it.show()
                    }
        }
    }

    private fun SnackbarMessageHolder.showSnackbar() {
        activity?.findViewById<View>(R.id.coordinator)?.let { coordinator ->
            val snackbar = WPSnackbar.make(
                    coordinator,
                    uiHelpers.getTextOfUiString(requireContext(), this.message),
                    Snackbar.LENGTH_LONG
            )
            if (this.buttonTitle != null) {
                snackbar.setAction(uiHelpers.getTextOfUiString(requireContext(), this.buttonTitle)) {
                    this.buttonAction.invoke()
                }
            }
            snackbar.show()
        }
    }

    private fun PreLoadPostContent.addWebViewCachingFragment() {
        val tag = "$blogId$postId"

        if (parentFragmentManager.findFragmentByTag(tag) == null) {
            parentFragmentManager.beginTransaction()
                    .add(ReaderPostWebViewCachingFragment.newInstance(blogId, postId), tag)
                    .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bookmarksSavedLocallyDialog?.dismiss()
        _binding = null
    }

    override fun getScrollableViewForUniqueIdProvision(): View {
        return binding.recyclerView
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RequestCodes.SITE_PICKER && resultCode == Activity.RESULT_OK && data != null) {
            val siteLocalId = data.getIntExtra(SitePickerActivity.KEY_LOCAL_ID, -1)
            viewModel.onReblogSiteSelected(siteLocalId)
        }
    }
}
