package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    assertEquals("رادار سنجش سطح", appName)
  }

  @Test
  fun `openAiRouterResponseParser handles string and array content blocks`() {
    val plainJson = """
      {"choices":[{"message":{"role":"assistant","content":"تحلیل ساختار ساروج تایید شد."}}]}
    """.trimIndent()
    assertEquals(
      "تحلیل ساختار ساروج تایید شد.",
      com.example.data.CustomOpenEndpointArchaeologyAiRepository.extractAssistantTextFromOpenAiJson(plainJson)
    )

    val arrayJson = """
      {"choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"بخش اول گزارش"},{"type":"text","text":"بخش دوم تحلیل SWIR"}]}}]}
    """.trimIndent()
    assertEquals(
      "بخش اول گزارش\nبخش دوم تحلیل SWIR",
      com.example.data.CustomOpenEndpointArchaeologyAiRepository.extractAssistantTextFromOpenAiJson(arrayJson)
    )
  }

  @Test
  fun `archaeologyAiRepository persists experiences and builds training context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = com.example.data.CustomOpenEndpointArchaeologyAiRepository(context)
    repo.saveTrainingNotes("در این دشت ساروج خاکستری است و گچ سطحی خطای کربنات می‌دهد.")
    assertEquals(
      "در این دشت ساروج خاکستری است و گچ سطحی خطای کربنات می‌دهد.",
      repo.loadTrainingNotes()
    )

    val exp = com.example.data.AiExperienceEntry(
      id = 101L,
      timestampMs = 1700000000000L,
      title = "دیوار آجری تپه شرقی",
      category = "سازه/ساروج واقعی",
      learnedRuleOrOutcome = "در عمق حدود ۴۰ سانتی خشت پخته و +۱۸ میکروتسلا مشاهده شد.",
      latitude = 35.171,
      longitude = 49.666,
      magDeltaUt = 18.0,
      stftPeakHz = 1.4,
      apparentDepthM = 0.14
    )
    val saved = repo.addExperience(exp)
    assertEquals(1, saved.size)
    assertEquals("دیوار آجری تپه شرقی", repo.loadExperiences().first().title)

    val block = com.example.data.CustomOpenEndpointArchaeologyAiRepository.buildTrainingContextBlock(
      trainingNotes = repo.loadTrainingNotes(),
      experiences = repo.loadExperiences()
    )
    org.junit.Assert.assertTrue(block.contains("ساروج خاکستری"))
    org.junit.Assert.assertTrue(block.contains("دیوار آجری تپه شرقی"))
    org.junit.Assert.assertTrue(block.contains("STFT=1.40Hz"))

    val afterDelete = repo.deleteExperience(101L)
    assertEquals(0, afterDelete.size)
  }
}
