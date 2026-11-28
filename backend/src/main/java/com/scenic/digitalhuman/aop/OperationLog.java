package com.scenic.digitalhuman.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解（第十二周任务 A2）：挂在写操作方法上，AOP 切面自动落审计表。
 * 业务代码零侵入——这是"用 AOP 切面自动记录，不手埋"的落地。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /** 动作码（如 like / favorite / qa:ask / file:upload） */
    String action();

    /** 目标类型（spot / qa / file / knowledge_doc） */
    String targetType() default "";

    /** 目标 ID 的 SpEL 表达式（如 "#id" / "#req.spotId"），解析失败记空不阻断业务 */
    String targetId() default "";
}
