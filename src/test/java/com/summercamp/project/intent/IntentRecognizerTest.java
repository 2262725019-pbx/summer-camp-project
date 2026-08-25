package com.summercamp.project.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.summercamp.project.weather.WeatherPeriod;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntentRecognizerTest {

    @Test
    void recognizesNaturalHelpQuestions() {
        IntentRecognizer recognizer = new IntentRecognizer(text -> Optional.empty());

        assertEquals(IntentType.HELP, recognizer.recognize("你有什么功能？").type());
        assertEquals(IntentType.HELP, recognizer.recognize("怎么用").type());
        assertEquals(IntentType.HELP, recognizer.recognize("功能列表").type());
    }

    @Test
    void recognizesWeatherLocationAndPeriodLocally() {
        IntentRecognizer recognizer = new IntentRecognizer(text -> Optional.empty());

        IntentResult result = recognizer.recognize("明天江西省宜春市袁州区天气怎么样");

        assertEquals(IntentType.WEATHER, result.type());
        assertEquals("江西省宜春市袁州区", result.location());
        assertEquals(WeatherPeriod.TOMORROW, result.weatherPeriod());
    }

    @Test
    void recognizesThreeDayWeatherAndImageCommandsLocally() {
        IntentRecognizer recognizer = new IntentRecognizer(text -> Optional.empty());

        IntentResult weather = recognizer.recognize("未来三天北京天气");
        IntentResult image = recognizer.recognize("帮我生成一张图片：月下的小猫");

        assertEquals(IntentType.WEATHER, weather.type());
        assertEquals("北京", weather.location());
        assertEquals(WeatherPeriod.THREE_DAYS, weather.weatherPeriod());
        assertEquals(IntentType.IMAGE_GENERATION, image.type());
        assertEquals("月下的小猫", image.prompt());
    }

    @Test
    void recognizesImageCapabilityQuestionsWithoutInventingAPrompt() {
        IntentRecognizer recognizer = new IntentRecognizer(text -> Optional.empty());

        IntentResult canGenerate = recognizer.recognize("你可以帮我生成一张图片吗？");
        IntentResult canDraw = recognizer.recognize("你会画图吗");

        assertEquals(IntentType.IMAGE_GENERATION, canGenerate.type());
        assertTrue(canGenerate.prompt().isBlank());
        assertEquals(IntentType.IMAGE_GENERATION, canDraw.type());
        assertTrue(canDraw.prompt().isBlank());
    }

    @Test
    void recognizesCommonImageAnalysisRequestsLocally() {
        IntentRecognizer recognizer = new IntentRecognizer(text -> Optional.empty());

        assertEquals(
                IntentType.IMAGE_ANALYSIS_REQUEST,
                recognizer.recognize("图片中有什么？").type());
        assertEquals(
                IntentType.IMAGE_ANALYSIS_REQUEST,
                recognizer.recognize("你能识别图片吗？").type());
        assertEquals(
                IntentType.IMAGE_ANALYSIS_REQUEST,
                recognizer.recognize("帮我看看刚才发的照片").type());
    }

    @Test
    void recognizesNumericThreeDayAndRainQueriesAndCleansLocation() {
        IntentRecognizer recognizer = new IntentRecognizer(text -> Optional.empty());

        IntentResult forecast = recognizer.recognize("能不能帮我查一下北京未来3天天气？");
        IntentResult rain = recognizer.recognize("江西省宜春市袁州区明天会不会下雨吗？");
        IntentResult missingLocation = recognizer.recognize("明天需要带伞吗？");

        assertEquals(IntentType.WEATHER, forecast.type());
        assertEquals("北京", forecast.location());
        assertEquals(WeatherPeriod.THREE_DAYS, forecast.weatherPeriod());
        assertEquals(IntentType.WEATHER, rain.type());
        assertEquals("江西省宜春市袁州区", rain.location());
        assertEquals(WeatherPeriod.TOMORROW, rain.weatherPeriod());
        assertEquals(IntentType.WEATHER, missingLocation.type());
        assertTrue(missingLocation.location().isBlank());
    }

    @Test
    void doesNotTreatImageOrWeatherDiscussionAsAnAction() {
        IntentRecognizer recognizer = new IntentRecognizer(text -> Optional.empty());

        assertEquals(IntentType.CHAT, recognizer.recognize("画蛇添足是什么意思？").type());
        assertEquals(IntentType.CHAT, recognizer.recognize("我喜欢《天气之子》这部电影").type());
    }

    @Test
    void ambiguousActionUsesClassifierAndClassifierFailureFallsBackToChat() {
        AtomicInteger calls = new AtomicInteger();
        IntentRecognizer classified = new IntentRecognizer(text -> {
            calls.incrementAndGet();
            return Optional.of(IntentResult.imageGeneration("一条龙"));
        });
        IntentRecognizer failed = new IntentRecognizer(text -> {
            throw new IllegalStateException("temporary failure");
        });

        assertEquals(IntentType.IMAGE_GENERATION, classified.recognize("帮我查询一些信息").type());
        assertEquals(1, calls.get());
        assertEquals(IntentType.CHAT, failed.recognize("帮我分析一下情况").type());
    }

    @Test
    void clearAndImageAnalysisRequestsAreDeterministic() {
        IntentRecognizer recognizer = new IntentRecognizer(text -> Optional.empty());

        assertEquals(IntentType.CLEAR_CONTEXT, recognizer.recognize("/clear").type());
        assertEquals(IntentType.IMAGE_ANALYSIS_REQUEST, recognizer.recognize("帮我识别一张图片").type());
        assertTrue(recognizer.recognize("普通聊天").location().isBlank());
    }

    @Test
    void keepsCompositeRequestsForTheMultiToolAgent() {
        IntentRecognizer recognizer = new IntentRecognizer(text -> Optional.empty());

        assertEquals(
                IntentType.CHAT,
                recognizer.recognize("查询北京明天天气，然后根据天气生成一张图片").type());
        assertEquals(
                IntentType.CHAT,
                recognizer.recognize("把明天上海的天气加入我的待办").type());
    }
}
