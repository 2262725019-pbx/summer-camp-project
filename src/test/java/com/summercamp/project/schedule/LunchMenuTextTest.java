package com.summercamp.project.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.summercamp.project.skill.nutrition.FoodCatalog;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LunchMenuTextTest {

    private final FoodCatalog foods = new FoodCatalog(new ObjectMapper());

    @Test
    void shouldBuildMenuWithAllCategoriesAndCalories() {
        String menu = LunchMenuText.build(LocalDate.of(2026, 8, 27), foods);

        assertTrue(menu.contains("今日午餐菜单"));
        assertTrue(menu.contains("主食"));
        assertTrue(menu.contains("蛋白质"));
        assertTrue(menu.contains("蔬菜"));
        assertTrue(menu.contains("水果"));
        assertTrue(menu.contains("千卡"));
    }

    @Test
    void shouldBeDeterministicForSameDate() {
        assertEquals(
                LunchMenuText.build(LocalDate.of(2026, 8, 27), foods),
                LunchMenuText.build(LocalDate.of(2026, 8, 27), foods));
    }

    @Test
    void shouldVaryMenuByDate() {
        assertNotEquals(
                LunchMenuText.build(LocalDate.of(2026, 8, 24), foods),
                LunchMenuText.build(LocalDate.of(2026, 8, 25), foods));
    }
}
