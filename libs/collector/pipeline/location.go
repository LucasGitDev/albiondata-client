package pipeline

import (
	"regexp"
	"strings"
)

func normalizeLocationID(v string) string {
	s := strings.TrimSpace(strings.Trim(v, ",."))
	if s == "" {
		return ""
	}
	reIsland := regexp.MustCompile(`(?i)@island@[0-9a-f-]{36}`)
	if m := reIsland.FindString(s); m != "" {
		return "@ISLAND@" + m[len("@island@"):]
	}
	reNumeric := regexp.MustCompile(`^[0-9]{3,6}$`)
	if reNumeric.MatchString(s) {
		return s
	}
	ls := strings.ToLower(s)
	if strings.HasPrefix(ls, "island-player-") ||
		strings.HasPrefix(ls, "@player-island") ||
		strings.HasPrefix(ls, "@island-") ||
		strings.HasPrefix(s, "BLACKBANK-") ||
		strings.HasSuffix(s, "-HellDen") ||
		strings.HasSuffix(s, "-Auction2") {
		return s
	}
	return ""
}
