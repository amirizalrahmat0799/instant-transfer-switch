package iso

import (
	"fmt"
	"strconv"
	"strings"
)

// ParseAmount turns an ISO 20022 amount ("150", "150.5", "150.50") into sen.
// Amounts never go through floating point.
func ParseAmount(s string) (int64, error) {
	s = strings.TrimSpace(s)
	whole, frac, hasFrac := strings.Cut(s, ".")
	if whole == "" || len(frac) > 2 || (hasFrac && frac == "") {
		return 0, fmt.Errorf("invalid amount %q", s)
	}
	for _, r := range whole + frac {
		if r < '0' || r > '9' {
			return 0, fmt.Errorf("invalid amount %q", s)
		}
	}
	w, err := strconv.ParseInt(whole, 10, 64)
	if err != nil || w > 1_000_000_000_000 {
		return 0, fmt.Errorf("invalid amount %q", s)
	}
	f := int64(0)
	if frac != "" {
		f, _ = strconv.ParseInt((frac + "0")[:2], 10, 64)
	}
	return w*100 + f, nil
}

// FormatAmount turns sen into the ISO 20022 decimal form: 15050 -> "150.50".
func FormatAmount(sen int64) string {
	return fmt.Sprintf("%d.%02d", sen/100, sen%100)
}
