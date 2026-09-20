package com.fuyun.pharmacy.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 配药采集入参（POST /dispenses/{no}/pick）：逐行追溯码采集（「无码不结」，2025-07 起合规口径）。
 *
 * @param items 逐行采集面，必填非空（@Valid 级联校验行内 prescriptionItemId 非空）
 */
public record PickRequest(@Valid @NotEmpty List<PickLine> items) {}
