package boem.dev.statelesspdfviewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import boem.dev.statelesspdfviewer.databinding.ItemPdfPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfPageAdapter(
    private val scope: CoroutineScope
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    private var renderer: PdfRenderer? = null

    // PdfRenderer.openPage() はスレッドセーフでないため排他制御する
    private val renderLock = Any()

    fun setRenderer(renderer: PdfRenderer) {
        this.renderer = renderer
        notifyDataSetChanged()
    }

    fun release() {
        renderer = null
    }

    override fun getItemCount(): Int = renderer?.pageCount ?: 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val r = renderer ?: return
        holder.bind(r, position, renderLock, scope)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.recycle()
    }

    class PageViewHolder(
        private val binding: ItemPdfPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null
        private var currentBitmap: Bitmap? = null

        fun bind(renderer: PdfRenderer, pageIndex: Int, lock: Any, scope: CoroutineScope) {
            renderJob?.cancel()
            binding.pageImage.setImageBitmap(null)
            binding.pageLoadingIndicator.visibility = View.VISIBLE
            currentBitmap?.recycle()
            currentBitmap = null

            renderJob = scope.launch {
                val screenWidth = binding.root.resources.displayMetrics.widthPixels

                val bitmap = withContext(Dispatchers.Default) {
                    try {
                        synchronized(lock) {
                            renderer.openPage(pageIndex).use { page ->
                                val scale = screenWidth.toFloat() / page.width
                                val pageHeight = (page.height * scale).toInt()
                                val bmp = Bitmap.createBitmap(
                                    screenWidth, pageHeight, Bitmap.Config.ARGB_8888
                                )
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp
                            }
                        }
                    } catch (_: Exception) {
                        null
                    }
                }

                currentBitmap = bitmap
                binding.pageImage.setImageBitmap(bitmap)
                binding.pageLoadingIndicator.visibility = View.GONE
            }
        }

        fun recycle() {
            renderJob?.cancel()
            renderJob = null
            binding.pageImage.setImageBitmap(null)
            currentBitmap?.recycle()
            currentBitmap = null
            binding.pageLoadingIndicator.visibility = View.VISIBLE
        }
    }
}
