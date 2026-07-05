package com.motionmouse.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Motion Mouse colour palette.
 *
 * Design direction: dark, premium, minimal.
 * Inspired by high-end peripheral software (Logitech G Hub, Razer Synapse)
 * but cleaner and less gamer-aesthetic.
 *
 * Primary accent: a cool electric blue — communicates precision and tech
 * without feeling toy-like.
 *
 * Surfaces use very dark greys rather than pure black —
 * pure black on OLED causes eye strain at night and makes
 * the UI feel flat. Subtle surface elevation via lightness steps.
 */

// --- Brand colours ---
val MotionBlue        = Color(0xFF2979FF)   // Primary accent — electric blue
val MotionBlueDim     = Color(0xFF1565C0)   // Pressed / disabled state
val MotionBlueLight   = Color(0xFF82B1FF)   // On-dark text tint

// --- Surfaces (dark theme) ---
val Surface0          = Color(0xFF0A0A0F)   // Background — near black with blue tint
val Surface1          = Color(0xFF13131A)   // Card / bottom sheet surface
val Surface2          = Color(0xFF1C1C26)   // Elevated card
val Surface3          = Color(0xFF252533)   // Input field / chip background

// --- Semantic colours ---
val Connected         = Color(0xFF00E676)   // Green — connection active
val ConnectedDim      = Color(0xFF00C853)
val Searching         = Color(0xFFFFAB00)   // Amber — searching / waiting
val Disconnected      = Color(0xFF546E7A)   // Muted blue-grey — inactive
val ErrorRed          = Color(0xFFFF5252)   // Error state

// --- Text ---
val TextPrimary       = Color(0xFFF5F5F7)   // Near white — primary labels
val TextSecondary     = Color(0xFF8E8EA0)   // Muted — secondary info
val TextTertiary      = Color(0xFF4A4A60)   // Very muted — hints / disabled

// --- Button colours ---
val LeftClickColor    = Color(0xFF2979FF)   // Blue — left click
val RightClickColor   = Color(0xFF212133)   // Dark — right click (less prominent)
val ButtonPressed     = Color(0xFF1565C0)   // Pressed feedback
val ButtonRipple      = Color(0x332979FF)   // Ripple overlay
