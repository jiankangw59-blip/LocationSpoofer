package com.vincenthzr.locationspoofer.viewmodel

import java.util.Locale

/** Editable coordinates are parsed with toDoubleOrNull; unlike a display label they are locale-neutral. */
internal fun coordinateInputText(degrees: Double): String =
    String.format(Locale.ROOT, "%.6f", degrees)
