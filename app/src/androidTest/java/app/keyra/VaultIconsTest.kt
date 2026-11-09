package app.keyra

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VaultIconsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun everyKdbxIdHasItsOwnReadableStandardResource() {
        val icons = VaultIcons(emptyMap())
        val resources = PredefinedIcon.entries.map { icon ->
            icons.resolve(Entry(UUID.randomUUID(), icon = icon)).resourceId
        }
        assertEquals(69, resources.size)
        assertEquals(69, resources.toSet().size)
        resources.forEachIndexed { id, resource ->
            assertEquals("vault_icon_$id", context.resources.getResourceEntryName(resource))
            assertNotNull(BitmapFactory.decodeResource(context.resources, resource))
        }
        assertEquals(R.drawable.vault_icon_48, icons.resolve(Group(UUID.randomUUID(), "Folder")).resourceId)
    }

    @Test fun customIconTakesPriorityForBothFoldersAndEntriesAndIsDownsampled() {
        val id = UUID.randomUUID()
        val icons = VaultIcons(mapOf(id to customIcon(1024, 512)))
        val folder = icons.resolve(Group(UUID.randomUUID(), "Folder", customIconUuid = id))
        val entry = icons.resolve(Entry(UUID.randomUUID(), customIconUuid = id))
        val bitmap = requireNotNull(folder.bitmap)
        assertSame(bitmap, entry.bitmap)
        assertTrue(bitmap.width <= 128)
        assertTrue(bitmap.height <= 128)
        assertEquals(2, bitmap.width / bitmap.height)
        assertEquals(Color.MAGENTA, bitmap.getPixel(0, 0))
    }

    @Test fun missingAndMalformedCustomIconsFallBackToAssignedStandardIcon() {
        val id = UUID.randomUUID()
        val entry = Entry(UUID.randomUUID(), icon = PredefinedIcon.World, customIconUuid = id)
        for (data in listOf<CustomIcon?>(null, CustomIcon(byteArrayOf(1, 2, 3), null, null))) {
            val icons = VaultIcons(if (data == null) emptyMap() else mapOf(id to data))
            val result = icons.resolve(entry)
            assertNull(result.bitmap)
            assertEquals(R.drawable.vault_icon_1, result.resourceId)
        }
    }

    @Test fun recycledRowReplacesCustomBitmapWithStandardIcon() {
        val id = UUID.randomUUID()
        val icons = VaultIcons(mapOf(id to customIcon(16, 16)))
        val custom = icons.resolve(Entry(UUID.randomUUID(), customIconUuid = id))
        instrumentation.runOnMainSync {
            val adapter = RowsAdapter({}, {})
            val themedContext = ContextThemeWrapper(context, R.style.Theme_Keyra)
            val holder = adapter.onCreateViewHolder(FrameLayout(themedContext), 0)
            adapter.submitList(listOf(
                Row(UUID.randomUUID(), "Entry", "", false, custom),
                Row(UUID.randomUUID(), "Folder", "", true, VaultIcon(R.drawable.vault_icon_48))
            ))
            adapter.onBindViewHolder(holder, 0)
            assertSame(custom.bitmap, (holder.binding.icon.drawable as BitmapDrawable).bitmap)
            adapter.onBindViewHolder(holder, 1)
            val expected = BitmapFactory.decodeResource(context.resources, R.drawable.vault_icon_48)
            assertTrue(expected.sameAs((holder.binding.icon.drawable as BitmapDrawable).bitmap))
        }
    }

    private fun customIcon(width: Int, height: Int): CustomIcon {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val bytes = ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }
        bitmap.recycle()
        return CustomIcon(bytes, null, null)
    }
}
