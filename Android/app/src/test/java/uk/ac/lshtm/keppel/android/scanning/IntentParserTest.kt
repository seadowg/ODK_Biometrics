package uk.ac.lshtm.keppel.android.scanning

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith
import uk.ac.lshtm.keppel.android.External

@RunWith(AndroidJUnit4::class)
class IntentParserTest {

    @Test
    fun `fast is false when param is false string`() {
        val intent = Intent().also {
            it.action = External.ACTION_SCAN
            it.putExtra(External.PARAM_FAST, "false")
        }

        val request = IntentParser.parse(intent)
        assertThat(request.fast, equalTo(false))
    }

    @Test
    fun `fast is false when param is arbitrary string`() {
        val intent = Intent().also {
            it.action = External.ACTION_SCAN
            it.putExtra(External.PARAM_FAST, "blah")
        }

        val request = IntentParser.parse(intent)
        assertThat(request.fast, equalTo(false))
    }

    @Test
    fun `blank isoTemplate is not included for MATCH`() {
        val intent = Intent().also {
            it.action = External.ACTION_MATCH
            it.putExtra(External.PARAM_ISO_TEMPLATE, "")
        }

        val request = IntentParser.parse(intent)
        assertThat((request as Request.Match).isoTemplates, equalTo(emptyList()))
    }

    @Test
    fun `isoTemplates is empty when list of MULTI_MATCH templates does not start at 1`() {
        val intent = Intent().also {
            it.action = External.ACTION_MULTI_MATCH
            it.putExtra(External.paramIsoTemplate(2), "blah")
        }

        val request = IntentParser.parse(intent)
        assertThat((request as Request.Match).isoTemplates, equalTo(emptyList()))
    }

    @Test
    fun `isoTemplates does not parse gaps for MULTI_MATCH`() {
        val intent = Intent().also {
            it.action = External.ACTION_MULTI_MATCH
            it.putExtra(External.paramIsoTemplate(1), "blah1")
            it.putExtra(External.paramIsoTemplate(3), "blah3")
        }

        val request = IntentParser.parse(intent)
        assertThat((request as Request.Match).isoTemplates, containsInAnyOrder("blah1"))
    }

    @Test
    fun `isoTemplates that are blank are filtered out for MULTI_MATCH`() {
        val intent = Intent().also {
            it.action = External.ACTION_MULTI_MATCH
            it.putExtra(External.paramIsoTemplate(1), "")
            it.putExtra(External.paramIsoTemplate(2), "blah2")
        }

        val request = IntentParser.parse(intent)
        assertThat((request as Request.Match).isoTemplates, containsInAnyOrder("blah2"))
    }
}
