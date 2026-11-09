package app.keyra

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.DatabaseElement
import java.util.UUID

data class VaultIcon(@DrawableRes val resourceId: Int, val bitmap: Bitmap? = null)

/** Scoped to one rendered database snapshot; never caches decrypted icons globally. */
class VaultIcons(private val customIcons: Map<UUID, CustomIcon>) {
    private val invalidIcons = hashSetOf<UUID>()
    private val cache = object : LruCache<UUID, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: UUID, value: Bitmap) = value.allocationByteCount
    }

    /** Called on the list-building worker, not while binding a RecyclerView row. */
    fun resolve(element: DatabaseElement): VaultIcon {
        val resource = standardResources.getOrElse(element.icon.ordinal) { R.drawable.vault_icon_0 }
        val id = element.customIconUuid ?: return VaultIcon(resource)
        val data = customIcons[id]?.data ?: return VaultIcon(resource)
        if (id in invalidIcons) return VaultIcon(resource)
        val bitmap = cache.get(id) ?: decode(data)?.also { cache.put(id, it) }
        if (bitmap == null) invalidIcons += id
        return VaultIcon(resource, bitmap)
    }

    private fun decode(data: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            // Bound allocations even if a database contains a very large image.
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        // Explicit references also keep every standard icon in resource-shrunk builds.
        internal val standardResources = intArrayOf(
            R.drawable.vault_icon_0,
            R.drawable.vault_icon_1,
            R.drawable.vault_icon_2,
            R.drawable.vault_icon_3,
            R.drawable.vault_icon_4,
            R.drawable.vault_icon_5,
            R.drawable.vault_icon_6,
            R.drawable.vault_icon_7,
            R.drawable.vault_icon_8,
            R.drawable.vault_icon_9,
            R.drawable.vault_icon_10,
            R.drawable.vault_icon_11,
            R.drawable.vault_icon_12,
            R.drawable.vault_icon_13,
            R.drawable.vault_icon_14,
            R.drawable.vault_icon_15,
            R.drawable.vault_icon_16,
            R.drawable.vault_icon_17,
            R.drawable.vault_icon_18,
            R.drawable.vault_icon_19,
            R.drawable.vault_icon_20,
            R.drawable.vault_icon_21,
            R.drawable.vault_icon_22,
            R.drawable.vault_icon_23,
            R.drawable.vault_icon_24,
            R.drawable.vault_icon_25,
            R.drawable.vault_icon_26,
            R.drawable.vault_icon_27,
            R.drawable.vault_icon_28,
            R.drawable.vault_icon_29,
            R.drawable.vault_icon_30,
            R.drawable.vault_icon_31,
            R.drawable.vault_icon_32,
            R.drawable.vault_icon_33,
            R.drawable.vault_icon_34,
            R.drawable.vault_icon_35,
            R.drawable.vault_icon_36,
            R.drawable.vault_icon_37,
            R.drawable.vault_icon_38,
            R.drawable.vault_icon_39,
            R.drawable.vault_icon_40,
            R.drawable.vault_icon_41,
            R.drawable.vault_icon_42,
            R.drawable.vault_icon_43,
            R.drawable.vault_icon_44,
            R.drawable.vault_icon_45,
            R.drawable.vault_icon_46,
            R.drawable.vault_icon_47,
            R.drawable.vault_icon_48,
            R.drawable.vault_icon_49,
            R.drawable.vault_icon_50,
            R.drawable.vault_icon_51,
            R.drawable.vault_icon_52,
            R.drawable.vault_icon_53,
            R.drawable.vault_icon_54,
            R.drawable.vault_icon_55,
            R.drawable.vault_icon_56,
            R.drawable.vault_icon_57,
            R.drawable.vault_icon_58,
            R.drawable.vault_icon_59,
            R.drawable.vault_icon_60,
            R.drawable.vault_icon_61,
            R.drawable.vault_icon_62,
            R.drawable.vault_icon_63,
            R.drawable.vault_icon_64,
            R.drawable.vault_icon_65,
            R.drawable.vault_icon_66,
            R.drawable.vault_icon_67,
            R.drawable.vault_icon_68
        )
    }
}
