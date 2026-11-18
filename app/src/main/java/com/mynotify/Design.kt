package com.mynotify

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Дизайн-система MyNotify (перенос из mynotify-interface/css/tokens.css).
 *
 * Принципы: тёмный холодный холст, глубина из контраста, а не из теней;
 * один акцент — синий, он только на интерактиве; статусы (эфир/тревога/
 * предупреждение) — сигнал, а не второй акцент.
 */
object Design {
    /** Радиусы: 12 поля, 14 кнопки, 16 карточки, pill — чипы. */
    const val R_FIELD = 12
    const val R_BUTTON = 14
    const val R_CARD = 16
    const val R_PILL = 999

    /**
     * Вес шрифта. Manrope и JetBrains Mono лежат переменными файлами
     * (ось wght), поэтому вес берётся через Typeface.create — он работает
     * с API 26+. Ниже — обычное начертание без оси, чтобы не упасть.
     */
    fun font(ctx: Context, res: Int, weight: Int): Typeface {
        val base = ResourcesCompat.getFont(ctx, res) ?: Typeface.SANS_SERIF
        return if (Build.VERSION.SDK_INT >= 26) Typeface.create(base, weight, false) else base
    }

    fun manrope(ctx: Context, weight: Int = 400): Typeface = font(ctx, R.font.manrope, weight)

    /** Моно — только метки, версии, IP, таймкоды. */
    fun mono(ctx: Context, weight: Int = 400): Typeface = font(ctx, R.font.jetbrains_mono, weight)

    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    /** Карточка: поверхность + хайрлайн. Теней нет — глубина из контраста. */
    fun cardBg(
        ctx: Context,
        radiusDp: Int = R_CARD,
        fill: Int = ContextCompat.getColor(ctx, R.color.mn_surface),
        stroke: Int = ContextCompat.getColor(ctx, R.color.mn_line)
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setStroke(dp(ctx, 1), stroke)
    }

    /** Поле ввода: поверхность на тон светлее + хайрлайн. */
    fun fieldBg(ctx: Context, radiusDp: Int = R_FIELD): GradientDrawable = cardBg(
        ctx, radiusDp,
        ContextCompat.getColor(ctx, R.color.mn_surface2),
        ContextCompat.getColor(ctx, R.color.mn_line)
    )

    /**
     * Фон кнопки одним StateListDrawable. Подменять фон в
     * setOnFocusChangeListener нельзя — так ломались скругления (v0.7.10).
     * Фокус с пульта: залитый акцент + белый штрих 3 dp.
     */
    fun buttonBg(
        ctx: Context,
        fill: Int,
        pressed: Int = fill,
        focused: Int? = null,
        radiusDp: Int = R_BUTTON
    ): StateListDrawable = StateListDrawable().apply {
        val r = dp(ctx, radiusDp).toFloat()
        val focusFill = focused ?: ContextCompat.getColor(ctx, R.color.mn_accent)
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(focusFill)
            cornerRadius = r
            setStroke(dp(ctx, 3), Color.WHITE)
        })
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            setColor(pressed)
            cornerRadius = r
        })
        addState(intArrayOf(), GradientDrawable().apply {
            setColor(fill)
            cornerRadius = r
        })
    }

    /**
     * Кольцо фокуса поверх элемента (foreground), фон не трогает.
     * Мигающий красный контур убран: на тёмном холсте он кричит и
     * спорит с синим акцентом. Кольцо спокойное, но жирное — с дивана.
     * Аниматор держим в замыкании слушателя: setTag(int) с ключом из
     * View.generateViewId() кидает IllegalArgumentException (краш v0.7.11).
     */
    fun attachFocusRing(v: View, radiusDp: Int) {
        val ctx = v.context
        v.isFocusable = true
        if (Build.VERSION.SDK_INT < 23) return
        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(ctx, 3), ContextCompat.getColor(ctx, R.color.mn_accent))
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }
        ring.alpha = 0
        v.foreground = ring
        v.setOnFocusChangeListener { _, focused -> ring.alpha = if (focused) 255 else 0 }
    }
}
