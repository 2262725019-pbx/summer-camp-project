package com.summercamp.project.skill.nutrition;

public record FoodItem(
        String id,
        String name,
        String category,
        double calories,
        double protein,
        double carbohydrates,
        double fat,
        String source) {
}
