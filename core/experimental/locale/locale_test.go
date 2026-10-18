package locale

import (
	"context"
	"fmt"
	"reflect"
	"strings"
	"testing"
)

func TestRegisteredLocalesKeepNativeMessages(t *testing.T) {
	cases := []struct {
		id, connected, running, needsLogin string
	}{
		{"en", "Connected", "Running", "Needs login"},
		{"fa", "\u0645\u062a\u0635\u0644", "\u062f\u0631 \u062d\u0627\u0644 \u0627\u062c\u0631\u0627", "\u0646\u06cc\u0627\u0632 \u0628\u0647 \u0648\u0631\u0648\u062f"},
		{"ru", "\u041f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u043e", "\u0420\u0430\u0431\u043e\u0442\u0430\u0435\u0442", "\u0422\u0440\u0435\u0431\u0443\u0435\u0442\u0441\u044f \u0432\u0445\u043e\u0434"},
		{"zh-Hans", "\u5df2\u8fde\u63a5", "\u8fd0\u884c\u4e2d", "\u9700\u8981\u767b\u5f55"},
		{"zh-Hant", "\u5df2\u9023\u7dda", "\u57f7\u884c\u4e2d", "\u9700\u8981\u767b\u5165"},
	}
	for _, tt := range cases {
		t.Run(tt.id, func(t *testing.T) {
			selected, ok := selectLocale(tt.id)
			if !ok || selected == nil || selected.Locale != tt.id {
				t.Fatalf("locale %q was not selected: %#v", tt.id, selected)
			}
			if selected.VPNStateText("connected") != tt.connected || selected.TailscaleStateText("Running") != tt.running || selected.TailscaleStateText("NeedsLogin") != tt.needsLogin {
				t.Fatalf("locale %q lost its native state messages", tt.id)
			}
			if selected.VPNStateText("unrecognized") != selected.Unknown || selected.TailscaleStateText("unrecognized") != selected.Unknown {
				t.Fatal("unknown states no longer use the selected locale")
			}
			value := reflect.ValueOf(selected).Elem()
			for i := 0; i < value.NumField(); i++ {
				if strings.TrimSpace(value.Field(i).String()) == "" {
					t.Errorf("empty localized field: %s", value.Type().Field(i).Name)
				}
			}
			formats := []struct {
				name, text string
				arguments  []any
			}{
				{"DeprecatedMessage", selected.DeprecatedMessage, []any{"feature-marker", "old-version-marker", "new-version-marker"}},
				{"DeprecatedMessageNoLink", selected.DeprecatedMessageNoLink, []any{"feature-marker", "old-version-marker", "new-version-marker"}},
				{"InsecureFeatureMessage", selected.InsecureFeatureMessage, []any{"feature-marker", "platform-marker"}},
				{"ExternalPathFeature", selected.ExternalPathFeature, []any{"path-marker", "platform-marker"}},
				{"TaildropReceiving", selected.TaildropReceiving, []any{"file-marker", "peer-marker"}},
				{"TaildropReceived", selected.TaildropReceived, []any{"file-marker", "peer-marker"}},
				{"TaildropSendCanceled", selected.TaildropSendCanceled, []any{"file-marker"}},
			}
			for _, message := range formats {
				formatted := fmt.Sprintf(message.text, message.arguments...)
				if strings.Contains(formatted, "%!") {
					t.Errorf("invalid %s format: %s", message.name, formatted)
				}
				for _, argument := range message.arguments {
					if !strings.Contains(formatted, argument.(string)) {
						t.Errorf("%s lost a format argument", message.name)
					}
				}
			}
		})
	}
}

func TestLocaleSelectionCompatibility(t *testing.T) {
	cases := map[string]string{
		"en_US.UTF-8": "en", "C": "en", "POSIX": "en",
		"zh_CN.UTF-8": "zh-Hans", "zh-CHS": "zh-Hans",
		"zh_TW": "zh-Hant", "zh-CHT": "zh-Hant", "zh-HK": "zh-Hant",
		"fa_IR": "fa", "ru_RU": "ru",
	}
	for input, want := range cases {
		selected, ok := selectLocale(input)
		if !ok || selected == nil || selected.Locale != want {
			t.Errorf("selection for %q: got %#v, want %q", input, selected, want)
		}
	}
	original := Current()
	t.Cleanup(func() { current.Store(original) })
	if !Set("zh-TW") || Current().Locale != "zh-Hant" || Current().VPNStateText("error") != "\u932f\u8aa4" {
		t.Fatal("global locale selection lost localized presentation")
	}
	ctx, ok := ContextWithLocale(context.Background(), "fa")
	if !ok || FromContext(ctx).Locale != "fa" || FromContext(ctx).Unknown != "\u0646\u0627\u0634\u0646\u0627\u062e\u062a\u0647" {
		t.Fatal("context locale selection lost localized presentation")
	}
	if selected, ok := selectLocale("not a locale"); ok || selected != nil {
		t.Fatal("invalid locale unexpectedly became valid")
	}
}
