package net.fstab.tachiai.platform.web

// Callers supply only closed native kinds/severities and numeric error codes.
// Deduplicate for the entire WebView lifetime; never retain provider strings.
internal class NativeDiagnosticBudget<T>(private val maximum: Int) {
    init { require(maximum > 0) }
    private val seen = mutableSetOf<T>()
    private var overflow = false
    private var reportedOverflow = false

    fun admit(event: T): Boolean {
        if (event in seen) return false
        if (seen.size >= maximum) { overflow = true; return false }
        seen.add(event)
        return true
    }

    fun takeLimitMarker(): Boolean {
        if (!overflow || reportedOverflow) return false
        reportedOverflow = true
        return true
    }
}
