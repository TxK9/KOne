package locale

// Preserve the accepted locale identifier while presenting English messages.
func init() {
	englishLocale := *defaultLocale
	englishLocale.Locale = "ru"
	localeRegistry["ru"] = &englishLocale
}
