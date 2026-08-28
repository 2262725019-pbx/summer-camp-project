package com.summercamp.project.schedule;

import com.summercamp.project.skill.nutrition.FoodCatalog;
import com.summercamp.project.skill.nutrition.FoodItem;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * 每日午餐菜单文案（纯函数，便于单测）。
 * 以日期为种子从本地食物库轮换主食/蛋白质/蔬菜/水果，
 * 同一天内容稳定、不同日期自动变化，无需依赖模型即可在到点准时生成。
 */
public final class LunchMenuText {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日");
    /** 每类常见份量（克），用于估算总热量。 */
    private static final int STAPLE_GRAMS = 150;
    private static final int PROTEIN_GRAMS = 120;
    private static final int VEGETABLE_GRAMS = 200;
    private static final int FRUIT_GRAMS = 150;
    /** 适合作为午餐主菜的蛋白质，排除偏早餐/点心的蛋奶类。 */
    private static final List<String> LUNCH_PROTEIN_EXCLUDED = List.of("egg", "milk", "yogurt");

    private LunchMenuText() {
    }

    public static String build(LocalDate date, FoodCatalog foods) {
        List<FoodItem> staples = foods.byCategory("carbohydrate");
        List<FoodItem> proteins = foods.byCategory("protein").stream()
                .filter(food -> !LUNCH_PROTEIN_EXCLUDED.contains(food.id()))
                .toList();
        List<FoodItem> vegetables = foods.byCategory("vegetable");
        List<FoodItem> fruits = foods.byCategory("fruit");

        long day = date.toEpochDay();
        FoodItem staple = staples.get((int) (day % staples.size()));
        FoodItem protein = proteins.get((int) ((day / 2) % proteins.size()));
        FoodItem vegetable = vegetables.get((int) ((day / 3) % vegetables.size()));
        FoodItem fruit = fruits.get((int) (day % fruits.size()));

        long calories = Math.round(
                caloriesOf(staple, STAPLE_GRAMS)
                        + caloriesOf(protein, PROTEIN_GRAMS)
                        + caloriesOf(vegetable, VEGETABLE_GRAMS)
                        + caloriesOf(fruit, FRUIT_GRAMS));

        return "🍱 今日午餐菜单（" + date.format(DATE_FORMAT) + " "
                + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA) + "）\n"
                + "主食：" + staple.name() + " " + STAPLE_GRAMS + "g\n"
                + "蛋白质：" + protein.name() + " " + PROTEIN_GRAMS + "g\n"
                + "蔬菜：" + vegetable.name() + " " + VEGETABLE_GRAMS + "g\n"
                + "水果：" + fruit.name() + " " + FRUIT_GRAMS + "g\n"
                + "合计约 " + calories + " 千卡（按常见份量估算）\n"
                + "搭配建议：主食 + 优质蛋白 + 深色蔬菜，少油少盐更健康。";
    }

    private static double caloriesOf(FoodItem food, int grams) {
        return food.calories() * grams / 100.0;
    }
}
