package com.famage.skydrop.ui.theme

import androidx.compose.ui.graphics.Color

// ─── SkyDrop Brand Accent Palette ──────────────────────────────────────────
val SkyBlue = Color(0xFF0EA5E9)           // --color-primary
val SkyBlueHover = Color(0xFF0284C7)      // --color-primary-hover
val TealAccent = Color(0xFF14B8A6)        // --color-accent (harmonious teal replacing harsh amber)
val TealAccentDark = Color(0xFF2DD4BF)    // bright teal for dark mode contrast
val SuccessGreen = Color(0xFF10B981)      // --color-success
val DangerRed = Color(0xFFEF4444)         // --color-danger

// ─── Dark Theme Surfaces ───────────────────────────────────────────────────
val DarkBgApp = Color(0xFF0B0F19)         // --bg-app dark
val DarkBgSurface = Color(0xFF111827)     // --bg-surface dark
val DarkBgElevated = Color(0xFF1F2937)    // --bg-surface-elevated dark
val DarkBgSubtle = Color(0xFF162032)      // --bg-surface-subtle dark
val DarkBorder = Color(0x1FFFFFFF)        // --border-color dark (12% white)
val DarkBorderVariant = Color(0xFF374151) // slightly visible separator

// ─── Dark Theme Text ───────────────────────────────────────────────────────
val DarkTextMain = Color(0xFFF3F4F6)      // --text-main dark
val DarkTextSecondary = Color(0xFF9CA3AF) // --text-secondary dark
val DarkTextMuted = Color(0xFF6B7280)     // --text-muted dark

// ─── Light Theme Surfaces ──────────────────────────────────────────────────
val LightBgApp = Color(0xFFF8FAFC)        // --bg-app light
val LightBgSurface = Color(0xFFFFFFFF)    // --bg-surface light
val LightBgElevated = Color(0xFFF1F5F9)   // --bg-surface-elevated light
val LightBgSubtle = Color(0xFFE2E8F0)     // --bg-surface-subtle light
val LightBorder = Color(0xFFE2E8F0)       // --border-color light

// ─── Light Theme Text ──────────────────────────────────────────────────────
val LightTextMain = Color(0xFF0F172A)      // --text-main light
val LightTextSecondary = Color(0xFF475569) // --text-secondary light
val LightTextMuted = Color(0xFF94A3B8)     // --text-muted light

// ─── Container / Semantic Colors ──────────────────────────────────────────
val SkyBlueDark = Color(0xFF075985)        // dark primary container
val SkyBlueContainerLight = Color(0xFFE0F2FE) // light primary container
val TealContainerLight = Color(0xFFCCFBF1) // light secondary container
val TealContainerDark = Color(0xFF134E4A)  // dark secondary container
val ErrorRedContainerLight = Color(0xFFFEE2E2)
val ErrorRedContainerDark = Color(0xFF7F1D1D)