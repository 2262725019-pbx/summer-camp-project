package com.summercamp.project.agent.artifact;

import com.summercamp.project.agent.model.HealthGoal;
import com.summercamp.project.rag.RagContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class HealthPlanAssembler {

    public HealthPlanArtifact assemble(
            HealthGoal goal,
            String weather,
            String nutrition,
            String exercise,
            RagContext rag,
            List<String> warnings) {
        StringBuilder content = new StringBuilder();
        content.append("目标与资料\n")
                .append("目标：").append(goal.goalType().chineseName())
                .append("；周期：").append(goal.days()).append(" 天")
                .append("；地点：").append(goal.location()).append('\n')
                .append("年龄：").append(goal.age()).append(" 岁；性别：").append(goal.gender())
                .append("；身高：").append(format(goal.heightCm())).append(" cm")
                .append("；体重：").append(format(goal.weightKg())).append(" kg\n")
                .append("每周训练：").append(goal.trainingDaysPerWeek()).append(" 次，每次 ")
                .append(goal.minutesPerSession()).append(" 分钟；每日 ")
                .append(goal.mealsPerDay()).append(" 餐\n\n")
                .append("天气与训练环境\n").append(weather).append("\n\n")
                .append("训练建议\n").append(exercise).append("\n\n")
                .append("饮食与营养建议\n").append(nutrition).append("\n\n")
                .append("七日执行安排\n");

        for (int day = 1; day <= goal.days(); day++) {
            boolean trainingDay = isTrainingDay(day, goal.trainingDaysPerWeek());
            content.append("第").append(day).append("天：")
                    .append(trainingDay ? trainingTheme(day) : "恢复日")
                    .append("；").append(trainingDay
                            ? "按训练日餐单执行，训练前后补水，出现不适立即停止。"
                            : "按休息日餐单执行，安排轻松步行和拉伸，不补偿性节食。")
                    .append('\n');
        }

        content.append("\n每日检查清单\n")
                .append("起床后查看当天实际天气；按计划完成用餐和训练/恢复；记录睡眠、饮水、训练感受；")
                .append("睡前根据疲劳程度调整次日强度。天气接口只提供未来三日预报，第 4 天起请每天重新确认天气。\n\n")
                .append("一周购物清单\n")
                .append("主食：米饭、燕麦、全麦面包；蛋白质：鸡蛋、牛奶、酸奶、鸡肉、瘦牛肉；")
                .append("蔬果：深色蔬菜、香蕉、苹果；脂肪来源：坚果、橄榄油。按实际餐量采购并注意食品保存。\n\n")
                .append("恢复与安全\n")
                .append("尽量保持规律睡眠，训练前热身、训练后放松；遇到暴雨、高温、雷电或空气质量不佳时改为室内低风险训练。")
                .append("本计划是面向健康成年人的一般生活建议，不作诊断，也不替代医生、注册营养师或康复师意见。\n\n")
                .append("参考依据\n")
                .append("中国居民膳食指南（2022）、国家卫生健康委体重管理指导原则、WHO 身体活动建议。")
                .append("本地 RAG 文档：")
                .append(rag.documentIds().isEmpty() ? "未命中" : String.join("、", rag.documentIds()))
                .append('。');

        List<String> allWarnings = new ArrayList<>(warnings == null ? List.of() : warnings);
        if (rag.documentIds().isEmpty()) {
            allWarnings.add("健康知识库未命中，本计划仅使用内置保守规则和已有 Skill 输出");
        }
        if (!allWarnings.isEmpty()) {
            content.append("\n\n降级与提醒\n");
            allWarnings.stream().distinct().forEach(warning -> content.append("- ").append(warning).append('\n'));
        }
        return new HealthPlanArtifact(
                goal.days() + "日" + goal.goalType().chineseName() + "健康生活计划",
                content.toString(),
                rag.documentIds(),
                allWarnings.stream().distinct().toList());
    }

    private boolean isTrainingDay(int day, int trainingDays) {
        if (trainingDays >= 7) {
            return true;
        }
        return switch (trainingDays) {
            case 1 -> day == 3;
            case 2 -> day == 2 || day == 5;
            case 3 -> day == 1 || day == 3 || day == 5;
            case 4 -> day == 1 || day == 2 || day == 4 || day == 6;
            case 5 -> day != 3 && day != 7;
            case 6 -> day != 4;
            default -> false;
        };
    }

    private String trainingTheme(int day) {
        return switch (day % 4) {
            case 1 -> "上肢推训练";
            case 2 -> "下肢训练";
            case 3 -> "上肢拉训练";
            default -> "全身与核心训练";
        };
    }

    private String format(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
    }
}
