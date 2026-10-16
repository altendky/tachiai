package net.fstab.tachiai.feature.presentation

import android.content.res.Configuration
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePageHeadingTest {
    @Test fun longNativeHeadingWrapsBesideAccessibleBackTargetAtLargeFont() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { fontScale = 2f })
            var backs = 0
            val heading = nativePageHeading(context, "A long native settings heading", "Back to access cases") { backs++ }
            activity.setContentView(heading)
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
            heading.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            heading.layout(0, 0, heading.measuredWidth, heading.measuredHeight)
            val back = heading.getChildAt(0) as ImageButton
            val title = heading.getChildAt(1) as TextView
            assertEquals("Back to access cases", back.contentDescription)
            assertTrue(back.width >= dp(48)); assertTrue(back.height >= dp(48))
            assertTrue(back.right <= title.left); assertTrue(title.right <= heading.width)
            assertTrue(title.lineCount > 1)
            assertTrue(ViewCompat.isAccessibilityHeading(title))
            assertTrue(back.isFocusable)
            assertTrue(back.performClick())
            assertEquals(1, backs)
        } }
    }
}
