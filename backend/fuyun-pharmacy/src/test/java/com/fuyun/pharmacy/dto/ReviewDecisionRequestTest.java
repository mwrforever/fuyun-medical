package com.fuyun.pharmacy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审方决策入参校验单测：opinion 长度上限=review_task.opinion 列宽 512（@Size 归入参——超长
 * 400 拦截防直达 varchar(512) 触发 DB 异常 500）。「可空」为冻结语义（缺意见归服务端
 * PH-1020 承载，禁 @NotBlank 400 先拦），与长度边界同面锚定。
 */
class ReviewDecisionRequestTest {

    /** 列宽上限字面量（与 @Size max=512 同源，超列宽即 DB 异常面） */
    private static final int OPINION_MAX_LENGTH = 512;

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    /** 违规集断言捷径：字段定位 opinion（无级联组件） */
    private Set<ConstraintViolation<ReviewDecisionRequest>> validate(String opinion) {
        return validator.validate(new ReviewDecisionRequest(opinion));
    }

    @Test
    @DisplayName("意见 512 字符边界内与 null 均通过校验（null 放行=可空冻结语义，归服务端 PH-1020）")
    void opinionWithinColumnWidthAndNullPassValidation() {
        assertThat(validate("x".repeat(OPINION_MAX_LENGTH))).isEmpty();
        assertThat(validate(null)).isEmpty();
    }

    @Test
    @DisplayName("意见 513 字符超列宽拒校验（违规定位于 opinion 字段）")
    void opinionBeyondColumnWidthRejectedByValidation() {
        var violations = validate("x".repeat(OPINION_MAX_LENGTH + 1));
        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .allSatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("opinion"));
    }
}
