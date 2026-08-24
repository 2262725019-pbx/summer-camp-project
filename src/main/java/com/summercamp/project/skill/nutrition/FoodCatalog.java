package com.summercamp.project.skill.nutrition;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class FoodCatalog {

    private static final String RESOURCE_PATH = "nutrition/foods.json";

    private final Map<String, FoodItem> foods;

    public FoodCatalog(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
        try (InputStream input = resource.getInputStream()) {
            List<FoodItem> loaded = objectMapper.readValue(input, new TypeReference<>() { });
            Map<String, FoodItem> byId = new LinkedHashMap<>();
            for (FoodItem food : loaded) {
                if (byId.putIfAbsent(food.id(), food) != null) {
                    throw new IllegalStateException("食物营养库存在重复 ID：" + food.id());
                }
            }
            foods = Map.copyOf(byId);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取本地食物营养库：" + RESOURCE_PATH, exception);
        }
    }

    public FoodItem require(String id) {
        FoodItem food = foods.get(id);
        if (food == null) {
            throw new IllegalArgumentException("食物营养库中不存在：" + id);
        }
        return food;
    }

    public int size() {
        return foods.size();
    }
}
