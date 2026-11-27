# Chrome Web Store Listing — Zemberek Türkçe Yazım ve Düzeltme Asistanı

> Last Updated: 2026-11-27

## Store Listing

**Extension Name** [REQUIRED]
Zemberek Türkçe Yazım ve Düzeltme Asistanı

**Short Description** [REQUIRED]
Türkçe yazım denetimi, ek ayırma/birleştirme, Türkçe karakter tamamlama (deasciifier) ve metin düzeltme asistanı.

**Detailed Description** [REQUIRED]
Zemberek Türkçe Yazım ve Düzeltme Asistanı, web sayfalarındaki metin alanlarında Türkçe yazım hatalarını, ayrı veya bitişik yazılması gereken ekleri (-de/-da, mi, ki) ve eksik Türkçe karakterleri anında tespit edip düzeltmenizi sağlar.

Temel Özellikler:
Bağlama duyarlı Türkçe yazım denetimi ve tek tıkla düzeltme önerileri
Ayrı veya bitişik yazılan -de/-da, soru eki mi ve bağlaç hatalarını mavi dalgalı çizgiyle gösterme
ASCII klavyeyle yazılmış Türkçe metinlerde ç, ğ, ı, İ, ö, ş, ü karakterlerini cümle bağlamına göre doğru tamamlama (Deasciify)
Yabancı dil (İngilizce, Almanca vb.) algılama koruması: Türkçe olmayan cümlelerde gereksiz düzeltme önermez ve tarayıcının kendi yazım denetimine bırakır
Tarayıcının çakışan alt çizgilerini otomatik gizleyerek temiz ve tek katmanlı görünüm sunma
Özel isim kesme işareti ve büyük/küçük harf uyumu denetimi
Google Docs ve PDF metinleri için her yerden erişilebilen Yan Panel Yazım Stüdyosu ve biçimbilimsel çözümleyici
Kişisel özel sözlük desteği (eklediğiniz kelimeler Türkçe ekleriyle birlikte tanınır ve cihazlarınız arasında senkronize edilir)

Nasıl Kullanılır:
Herhangi bir web sitesinde yazı yazarken renkli dalgalı çizgilerin üzerine gelerek önerilen düzeltmeyi tek tıkla uygulayın ve istediğiniz an Ctrl+Z ile geri alın. Seçili metinleri sağ tıklama menüsünden anında düzeltebilir veya araç çubuğundaki Zemberek simgesinden Yan Panel Stüdyosu'nu açabilirsiniz.

Gizlilik Güvencesi:
Yazdığınız hiçbir metin sunuculara gönderilmez; tüm dil çözümleme ve düzeltme işlemleri tamamen kendi bilgisayarınızda çevrimdışı olarak çalışır.

**Category** [REQUIRED]
Productivity

**Single Purpose** [REQUIRED]
Checks and corrects Turkish spelling, clitic spacing, and missing Turkish diacritics directly inside web text editors and a side-panel scratchpad.

**Primary Language** [REQUIRED]
Turkish

## Graphics & Assets

| Asset | Dimensions | Status | Filename |
|-------|-----------|--------|----------|
| Store Icon [REQUIRED] | 128×128 PNG | ✅ Ready | `dist/icons/icon-128.png` |
| Screenshot 1 [REQUIRED] | 1280×800 | ⬜ Not created | |
| Small Promo Tile [RECOMMENDED] | 440×280 | ⬜ Not created | |

## Permissions Justification

| Permission | Type | Justification |
|------------|------|---------------|
| `offscreen` | permissions | Hosts a single shared background language-checking worker across open tabs so Turkish dictionary models are loaded only once without slowing down web pages. |
| `storage` | permissions | Saves the user's custom Turkish dictionary words, selected checking mode, and scratchpad draft locally and across signed-in Chrome devices. |
| `contextMenus` | permissions | Adds right-click menu actions allowing users to normalize or restore Turkish characters in selected text on any page. |
| `activeTab` | permissions | Applies user-triggered right-click normalization/deasciification results to the active tab and refreshes editor underlines when settings change. |
| `sidePanel` | permissions | Provides the persistent Zemberek Writing Studio side panel for checking and normalizing longer texts or clipboard content alongside Google Docs and PDFs. |
| `http://*/*`, `https://*/*` | host_permissions | Injects non-destructive wavy spelling/grammar underlines and hover suggestion cards into editable text fields across websites where the user writes Turkish text. |

## Privacy & Data Use

### Data Collection

**Does the extension collect user data?** No (all text checking runs 100% locally on-device; only user-added custom dictionary words and extension preferences are synced through the user's own Chrome account via `chrome.storage.sync`).

### Data Use Certification
- [x] Data is NOT sold to third parties
- [x] Data is NOT used for purposes unrelated to the extension's core functionality
- [x] Data is NOT used for creditworthiness or lending purposes

## Version History

| Version | Date | Changes | Status |
|---------|------|---------|--------|
| 0.2.4 | 2026-11-27 | Added residual spellcheck fallback in Studio mode so unattested-bigram edit-distance-1 typos (e.g. `konrol -> kontrol`, `kitablar -> kitaplar`) are caught in both in-page checking and `Düzelt` normalization, with lowercase casing tie-breaking. | Draft |
| 0.2.2 | 2026-11-27 | Updated extension toolbar, store, and UI branding to the official Zemberek spiral mainspring SVG/PNG icon set (16/32/48/128px). | Draft |
| 0.2.1 | 2026-11-27 | Fixed BFCache port disconnect runtime.lastError in offscreen worker, eliminated extra scrollbar on 100vh pages and Side Panel, and enforced strict `connect-src 'self'` local-only CSP. | Draft |
| 0.2.0 | 2026-11-27 | Streamlined Popup & Side Panel UI with icon actions, moved engine/circumflex controls to Extension Options, fixed Side Panel morphological analysis wrapping, suppressed overlapping native Chrome spellcheck squiggles on Turkish fields, and added automatic non-Turkish text gating. | Draft |
| 0.1.0 | 2026-11-27 | Initial Manifest V3 release with on-device Rust/Wasm Turkish morphology, spellchecker, lattice deasciifier, contextual normalizer, non-destructive CSS Custom Highlights, textarea mirror overlay, and Side Panel Writing Studio. | Draft |
