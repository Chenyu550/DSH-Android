// On-demand translation of reasoning text (#15; row `android-translate`, bundle-android). dsh's
// `translator` service (@deepseek-ai/dsh-experimental-translator, row `translator`) sends one text to
// an anonymous browser endpoint (Bing's Edge translator, else Google's): no key, no model call, no
// session. dsh's own consumer (dsh-experimental-client-ui-cot-translation) is a web-client Remote and
// is not shipped; this row is the SDK side: `android/reasoning/translate { text, targetLanguage }`.
// Texts over the service's per-request limit are split at paragraph / line breaks and translated in
// order. Failures answer `{ error: { code, message } }` (codes: TranslationErrorCode) instead of
// failing the request, so the app can say what went wrong; the source text is never logged.

export const name = 'android-translate'
export const inject = ['translator']

export const METHOD = 'android/reasoning/translate'
/** Tried in order; the next one only when the previous could not be reached (blocked in some regions). */
export const PROVIDERS = Object.freeze(['bing', 'google'])
const UNREACHABLE = new Set(['TRANSLATION_REQUEST_FAILED', 'TRANSLATION_TIMEOUT', 'TRANSLATION_HTTP_ERROR'])
const TAG = /^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$/

/**
 * Splits [text] into pieces of at most [max] UTF-16 units, at the last paragraph break, else line
 * break, else space before the limit (a hard cut only for one unbroken run). Joining the pieces gives
 * back [text]: separators stay at the end of the piece before them.
 */
export function chunks(text, max) {
  const out = []
  let rest = text
  while (rest.length > max) {
    const window = rest.slice(0, max)
    let cut = -1
    for (const sep of ['\n\n', '\n', ' ']) {
      const at = window.lastIndexOf(sep)
      if (at > 0) { cut = at + sep.length; break }
    }
    if (cut <= 0) {
      cut = max
      // Never split a surrogate pair.
      const code = rest.charCodeAt(cut - 1)
      if (code >= 0xd800 && code <= 0xdbff) cut--
    }
    out.push(rest.slice(0, cut))
    rest = rest.slice(cut)
  }
  if (rest.length > 0 || out.length === 0) out.push(rest)
  return out
}

/** One piece; whitespace around it is kept as is (providers trim it), so the pieces join back. */
async function translatePiece(translator, piece, targetLanguage, provider, signal) {
  const body = piece.trim()
  if (body === '') return piece
  const lead = piece.slice(0, piece.indexOf(body))
  const trail = piece.slice(lead.length + body.length)
  const spec = translator.resolve({ text: body, targetLanguage, provider })
  return lead + await translator.translate(spec, signal) + trail
}

/**
 * Translates [text] into [targetLanguage] with the first reachable provider.
 * @returns `{ text, provider }` or `{ error: { code, message } }`.
 */
export async function translateReasoning(translator, params, signal) {
  const text = params?.text
  const targetLanguage = params?.targetLanguage
  if (typeof text !== 'string' || typeof targetLanguage !== 'string' || !TAG.test(targetLanguage))
    throw new TypeError(`${METHOD} requires text and a BCP 47 targetLanguage`)
  const pieces = chunks(text, Math.max(2, translator.maxTextChars))
  let last
  for (const provider of PROVIDERS) {
    try {
      const out = []
      for (const piece of pieces) out.push(await translatePiece(translator, piece, targetLanguage, provider, signal))
      return { text: out.join(''), provider }
    } catch (error) {
      signal?.throwIfAborted()
      last = error
      if (!UNREACHABLE.has(error?.code)) break
    }
  }
  return { error: { code: typeof last?.code === 'string' ? last.code : 'TRANSLATION_FAILED', message: String(last?.message ?? last) } }
}

export function apply(ctx) {
  ctx.on('sdk-extensions/resolve', method => method !== METHOD ? undefined
    : (params, signal) => translateReasoning(ctx.translator, params, signal))
}
