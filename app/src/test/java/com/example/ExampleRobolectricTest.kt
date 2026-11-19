package com.example

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import androidx.test.core.app.ApplicationProvider
import com.example.ui.setup.RestrictedTerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("VoxKey", appName)
  }

  @Test
  fun `restricted terminal view exposes TYPE_NULL and denies text extraction`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val terminal = RestrictedTerminalView(context)
    val editorInfo = EditorInfo()
    val ic = terminal.onCreateInputConnection(editorInfo)

    assertEquals(InputType.TYPE_NULL, editorInfo.inputType)
    assertNull(ic.getExtractedText(ExtractedTextRequest(), 0))
    assertNull(ic.getSelectedText(0))
    assertNull(ic.getTextBeforeCursor(20, 0))
    assertNull(ic.getTextAfterCursor(20, 0))
  }
}
