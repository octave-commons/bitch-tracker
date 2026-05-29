// Discord Bot Slur Detection Patterns
// ===================================
// These patterns replace the previous single regex:
//   /nigge.|tr.nny|f.ggot|k.ke|r.tard|jew/i
//
// Improvements:
// - Split into individual patterns for clearer alerts (e.g., "N slur detected")
// - Word boundaries (\b) prevent false positives like "k ke" in "luck keep"
// - Exact spellings replace dot wildcards that matched spaces/any character
// - Character classes catch common leet-speak obfuscations (n1gger, f@ggot, etc.)
// - Optional trailing 's' handles plural forms

const slurPatterns = [
  {
    name: 'N slur',
    // Matches: nigger, niggers, n!gger, n1gger, n|gger, nigg3r, etc.
    pattern: /\bn+[i!1|]+g+[g3]+[e3]+r+(s?)\b/i
  },
  {
    name: 'F slur',
    // Matches: faggot, faggots, f@ggot, f*ggot, f4ggot, fagg0t, etc.
    pattern: /\bf+[a@4*]+g+[g3]+[o0]+t+(s?)\b/i
  },
  {
    name: 'K slur',
    // Matches: kike, kikes, k!ke, k1ke, k|ke, kik3, etc.
    pattern: /\bk+[i!1|]+k+[e3]+(s?)\b/i
  },
  {
    name: 'T slur',
    // Matches: tranny, trannies, tr@nny, tr4nny, trann!, trann1es, etc.
    pattern: /\bt+r+[a@4*]+n+n+[iy!1]+(e?s?)\b/i
  },
  {
    name: 'R slur',
    // Matches: retard, retards, retarded, retardation,
    //          ret@rd, ret4rd, ret3rd, etc.
    pattern: /\br+[e3]+t+[a@4*]+r+d+(s|ed|ation)?\b/i
  },
  {
    name: 'J slur',
    // Matches: jew
    // NOTE: "jew" is also a legitimate self-identifier. This will flag
    // standalone uses of the word in any context. Consider whether this
    // pattern should be treated differently from the others.
    pattern: /\bj+e+w+\b/i
  }
];

// ============================================================================
// Usage Example
// ============================================================================

/**
 * Checks a message for slurs and returns the first match found.
 * @param {string} text - The message content to check
 * @returns {string|null} - The slur label (e.g., "K slur") or null if clean
 */
function detectSlur(text) {
  for (const { name, pattern } of slurPatterns) {
    if (pattern.test(text)) {
      return name;
    }
  }
  return null;
}

/**
 * Checks a message and returns ALL slur matches found.
 * @param {string} text - The message content to check
 * @returns {string[]} - Array of slur labels (e.g., ["N slur", "F slur"])
 */
function detectAllSlurs(text) {
  const matches = [];
  for (const { name, pattern } of slurPatterns) {
    if (pattern.test(text)) {
      matches.push(name);
    }
  }
  return matches;
}

// ============================================================================
// Combined Single Regex (Alternative)
// ============================================================================
// If the bot implementation prefers a single regex test, use this:
//
// const slurRegex = /\b(n+[i!1|]+g+[g3]+[e3]+r+(s?))\b|\b(f+[a@4*]+g+[g3]+[o0]+t+(s?))\b|\b(k+[i!1|]+k+[e3]+(s?))\b|\b(t+r+[a@4*]+n+n+[iy!1]+(e?s?))\b|\b(r+[e3]+t+[a@4*]+r+d+(s?))\b|\b(j+e+w+)\b/gi;
//
// However, the individual pattern approach is recommended because:
// 1. It allows specific slur labels in alerts ("N slur" instead of raw regex)
// 2. It's easier to maintain and debug
// 3. It allows different handling per slur type if needed
//
// ============================================================================

module.exports = {
  slurPatterns,
  detectSlur,
  detectAllSlurs
};
