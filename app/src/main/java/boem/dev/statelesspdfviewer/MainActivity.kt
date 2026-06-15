package boem.dev.statelesspdfviewer

import android.content.ContentResolver
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import boem.dev.statelesspdfviewer.databinding.ActivityMainBinding
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private lateinit var pageAdapter: PdfPageAdapter

    private var searchJob: Job? = null
    private var searchResults: List<Int> = emptyList()
    private var currentResultIndex = -1

    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        PDFBoxResourceLoader.init(applicationContext)

        setupRecyclerView()
        setupSearchBar()
        setupPageBar()
        openPdfFromIntent()
    }

    private fun setupRecyclerView() {
        pageAdapter = PdfPageAdapter(lifecycleScope)
        binding.pdfRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pageAdapter
            isNestedScrollingEnabled = false
            clipToPadding = false
            val bottomPad = resources.displayMetrics.heightPixels / 3
            setPadding(0, 0, 0, bottomPad)
        }
    }

    private fun setupSearchBar() {
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            val triggered = actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event?.keyCode == KeyEvent.KEYCODE_ENTER
                            && event.action == KeyEvent.ACTION_DOWN)
            if (triggered) {
                startSearch(binding.searchInput.text?.toString().orEmpty())
                hideKeyboard()
                true
            } else {
                false
            }
        }

        binding.prevButton.setOnClickListener { navigateResult(-1) }
        binding.nextButton.setOnClickListener { navigateResult(+1) }
    }

    private fun setupPageBar() {
        binding.pageInput.setOnEditorActionListener { _, actionId, event ->
            val triggered = actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event?.keyCode == KeyEvent.KEYCODE_ENTER
                            && event.action == KeyEvent.ACTION_DOWN)
            if (triggered) {
                goToPage(binding.pageInput.text?.toString().orEmpty())
                hideKeyboard()
                true
            } else {
                false
            }
        }

        binding.prevPageButton.setOnClickListener { navigatePage(-1) }
        binding.nextPageButton.setOnClickListener { navigatePage(+1) }
    }

    private fun openPdfFromIntent() {
        val uri = intent.data
        if (uri == null) {
            showError(getString(R.string.error_no_pdf))
            return
        }

        try {
            fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("openFileDescriptor returned null")
            pdfRenderer = PdfRenderer(fileDescriptor!!)

            val pageCount = pdfRenderer!!.pageCount
            pageAdapter.setRenderer(pdfRenderer!!)

            supportActionBar?.title = resolveFileName(uri)
            supportActionBar?.subtitle = getString(R.string.page_count_format, pageCount)

            binding.pdfRecyclerView.visibility = View.VISIBLE
            binding.errorText.visibility = View.GONE
        } catch (e: Exception) {
            showError(getString(R.string.error_open_failed))
        }
    }

    private fun resolveFileName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment ?: "PDF"
    }

    // ──────── メニュー ────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_menu) return super.onOptionsItemSelected(item)

        val anchor = binding.toolbar.findViewById<View>(R.id.action_menu) ?: binding.toolbar
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.popup_menu, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_search -> { openSearchBar(); true }
                R.id.menu_page -> { openPageBar(); true }
                else -> false
            }
        }
        popup.show()
        return true
    }

    // ──────── 検索バー ────────

    private fun openSearchBar() {
        if (binding.pageBar.visibility == View.VISIBLE) closePageBar()
        binding.searchBar.visibility = View.VISIBLE
        binding.searchInput.requestFocus()
        showKeyboard(binding.searchInput)
    }

    private fun closeSearchBar() {
        binding.searchBar.visibility = View.GONE
        binding.searchInput.text?.clear()
        clearSearchState()
        hideKeyboard()
    }

    // ──────── ページナビゲーションバー ────────

    private fun openPageBar() {
        if (binding.searchBar.visibility == View.VISIBLE) closeSearchBar()
        binding.pageBar.visibility = View.VISIBLE
        val lm = binding.pdfRecyclerView.layoutManager as? LinearLayoutManager
        currentPage = lm?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        refreshPageUI()
        binding.pageInput.requestFocus()
        binding.pageInput.selectAll()
        showKeyboard(binding.pageInput)
    }

    private fun closePageBar() {
        binding.pageBar.visibility = View.GONE
        binding.pageInput.text?.clear()
        hideKeyboard()
    }

    private fun navigatePage(direction: Int) {
        val pageCount = pdfRenderer?.pageCount ?: return
        val lm = binding.pdfRecyclerView.layoutManager as? LinearLayoutManager ?: return
        currentPage = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        currentPage = (currentPage + direction).coerceIn(0, pageCount - 1)
        scrollToPage(currentPage)
        refreshPageUI()
    }

    private fun goToPage(input: String) {
        val pageCount = pdfRenderer?.pageCount ?: return
        val pageNumber = input.toIntOrNull() ?: return
        currentPage = (pageNumber - 1).coerceIn(0, pageCount - 1)
        scrollToPage(currentPage)
        refreshPageUI()
    }

    private fun refreshPageUI() {
        val pageCount = pdfRenderer?.pageCount ?: 0
        binding.pageCountText.text = getString(R.string.page_of_total, pageCount)
        binding.pageInput.setText((currentPage + 1).toString())
        binding.pageInput.setSelection(binding.pageInput.text?.length ?: 0)
        binding.prevPageButton.isEnabled = currentPage > 0
        binding.nextPageButton.isEnabled = currentPage < pageCount - 1
    }

    // ──────── テキスト検索 ────────

    private fun startSearch(query: String) {
        searchJob?.cancel()
        clearSearchState()

        if (query.isBlank()) return

        val uri = intent.data ?: return
        binding.searchResultCount.text = getString(R.string.searching)
        binding.prevButton.isEnabled = false
        binding.nextButton.isEnabled = false

        searchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                extractMatchingPages(uri, query)
            }

            searchResults = results
            currentResultIndex = if (results.isNotEmpty()) 0 else -1
            refreshSearchUI()

            if (results.isNotEmpty()) {
                scrollToPage(results[0])
            }
        }
    }

    private fun extractMatchingPages(uri: Uri, query: String): List<Int> {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { doc ->
                    val stripper = PDFTextStripper()
                    val lowerQuery = query.lowercase()
                    (0 until doc.numberOfPages).filter { pageIndex ->
                        stripper.startPage = pageIndex + 1
                        stripper.endPage = pageIndex + 1
                        stripper.getText(doc).lowercase().contains(lowerQuery)
                    }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun navigateResult(direction: Int) {
        if (searchResults.isEmpty()) return
        currentResultIndex = (currentResultIndex + direction).coerceIn(0, searchResults.size - 1)
        refreshSearchUI()
        scrollToPage(searchResults[currentResultIndex])
    }

    private fun scrollToPage(pageIndex: Int) {
        (binding.pdfRecyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(pageIndex, 0)
    }

    private fun clearSearchState() {
        searchResults = emptyList()
        currentResultIndex = -1
        binding.searchResultCount.text = ""
        binding.prevButton.isEnabled = false
        binding.nextButton.isEnabled = false
    }

    private fun refreshSearchUI() {
        when {
            searchResults.isEmpty() -> {
                binding.searchResultCount.text = getString(R.string.no_results)
                binding.prevButton.isEnabled = false
                binding.nextButton.isEnabled = false
            }
            else -> {
                binding.searchResultCount.text = getString(
                    R.string.result_format, currentResultIndex + 1, searchResults.size
                )
                binding.prevButton.isEnabled = currentResultIndex > 0
                binding.nextButton.isEnabled = currentResultIndex < searchResults.size - 1
            }
        }
    }

    // ──────── エラー表示 ────────

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
        binding.pdfRecyclerView.visibility = View.GONE
    }

    // ──────── キーボード ────────

    private fun showKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    // ──────── ライフサイクル（ステートレス設計） ────────

    override fun onPause() {
        super.onPause()
        purgeAllCaches()
    }

    override fun onDestroy() {
        super.onDestroy()
        searchJob?.cancel()
        pageAdapter.release()
        pdfRenderer?.close()
        pdfRenderer = null
        fileDescriptor?.close()
        fileDescriptor = null
    }

    private fun purgeAllCaches() {
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
    }
}
