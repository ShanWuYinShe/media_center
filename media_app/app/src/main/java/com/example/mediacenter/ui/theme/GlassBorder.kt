package com.example.mediacenter.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val CardShape = RoundedCornerShape(12.dp)

fun Modifier.glassCardBackground(): Modifier = composed {
    this
        .clip(CardShape)
        .background(CardGray, CardShape)
}

fun Modifier.glassCardFocused(): Modifier = composed {
    this
        .clip(CardShape)
        .background(AppleBlue, CardShape)
        .border(BorderStroke(2.dp, FocusBorder), CardShape)
}
