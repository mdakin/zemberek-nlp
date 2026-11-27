# Zemberek Türkçe Yazım ve Düzeltme Asistanı (Chrome MV3 v0.2.5) — Kurulum, Test ve Davranış Rehberi

Bu klasördeki **`zemberek-chrome-extension-v0.2.5.zip`** paketi, Zemberek'in Rust + WebAssembly motorunu (Morfoloji, Bağlamsal Normalizer, Lattice Deasciifier, Sözdizimsel Belirsizlik Giderici ve Yazım Denetleyicisi) doğrudan Chrome içinde **%100 çevrimdışı** çalıştıran eklentinin kurulabilir sürümüdür.

---

## 1. Hızlı Kurulum (1 Dakika — Windows / macOS / Linux)

1. **`zemberek-chrome-extension-v0.2.5.zip`** dosyasını bilgisayarınıza indirin ve bir klasöre çıkartın (ör. masaüstünde veya İndirilenler'de `zemberek-chrome-extension-v0.2.5` klasörü oluşacak şekilde; klasörün içinde doğrudan `manifest.json` görünmelidir).
   - *Linux / macOS terminal için:* `unzip zemberek-chrome-extension-v0.2.5.zip -d zemberek-chrome-extension`
2. Google Chrome adres çubuğuna **`chrome://extensions`** yazıp Enter'a basın.
3. Sağ üst köşedeki **Geliştirici modu (Developer mode)** anahtarını **AÇIK** konuma getirin.
4. Sol üstte beliren **Paketlenmemiş öğe yükle (Load unpacked)** düğmesine tıklayın ve çıkarttığınız klasörü seçin.
   - *Mevcut kurulumu güncellerken:* Aynı klasörün üzerine çıkarttıysanız `chrome://extensions` sayfasında Zemberek kartındaki **Yenile (↻)** simgesine tıklamanız yeterlidir.
5. *(İsteğe bağlı)* Chrome araç çubuğundaki yapboz (🧩 Eklentiler) simgesine tıklayıp **Zemberek Türkçe Yazım ve Düzeltme Asistanı** yanındaki sabitleme (📌) simgesine basarak simgeyi araç çubuğuna sabitleyin.

---

## 2. Temel Özellikler ve Test Senaryoları

1. **Sayfa İçi Canlı Denetim (`<textarea>`, `<input>`, Gmail, GitHub, X, Notion, Slack vb.):**
   - Herhangi bir metin kutusuna Türkçe hatalı veya ASCII klavyeyle yazılmış bir cümle girin:
     - Örnek: `Yarin sabah kutuphaneye gidicem, herkez bende toplantiya katilicam dedi olurmu? Hemen konrol yapalim.`
   - **Renk kodları:**
     - **Kırmızı dalgalı çizgi (`spelling`):** Yazım hatası (`herkez → herkes`, `kitablar → kitaplar`, `konrol → kontrol`)
     - **Mavi dalgalı çizgi (`clitic`):** Ayrı/bitişik ek ve bağlaç hatası (`bende → ben de`, `olurmu → olur mu`, `birşey → bir şey`)
     - **Mor dalgalı çizgi (`deasc`):** Eksik Türkçe karakter veya günlük ağız (`Yarin → Yarın`, `kutuphaneye → kütüphaneye`, `gidicem → gideceğim`)
   - Altı çizili kelimenin üzerine tıklayarak (veya gelerek) öneri kartından tek tıkla düzeltin; **`Ctrl+Z` / `Cmd+Z`** ile geri almanın korunduğunu doğrulayın.
2. **Araç Çubuğu Açılır Penceresi (Popup):**
   - Araç çubuğundaki Zemberek sarmal simgesine tıklayın: hızlı metin alanında **Düzelt**, **Deasciify**, **Kopyala** ve **Temizle** düğmelerini, **Özel Sözlük** ekleme özelliğini ve sağ alttaki **Yan Panelde Aç** simgesini deneyin.
3. **Yan Panel Yazım Stüdyosu (Google Docs / PDF / Uzun Metinler):**
   - Yan panelde **Düzelt**, **Denetle**, **Deasciify** ve **Çözümle** (cümle içi biçimbilimsel çözümleme + belirsizlik giderme) özelliklerini test edin.
4. **Eklenti Ayarları (`Options`):**
   - Popup veya Yan Panel sağ üstündeki **⚙️ Ayarlar** simgesinden **Motor Modu** (`Studio` / `Core Lite`), **Şapkalı Harf (`â/î/û`)** modu, yabancı dil koruması ve Chrome alt çizgi gizleme tercihlerini değiştirebilirsiniz.

---

## 3. Çoklu Yazım Denetleyici (Multiple Spellcheckers) Uyumluluğu

Tarayıcıda aynı anda birden fazla yazım denetleyicisi (Chrome'un yerleşik Hunspell/OS denetleyicisi, çok dilli tarayıcı sözlükleri veya üçüncü parti eklentiler) bulunabilir. Zemberek çakışmaları önlemek için aşağıdaki politikayı uygular:

1. **Chrome Yerleşik Yazım Denetleyicisi ile El Sıkışma (`suppressNativeSpellcheck = true` varsayılan):**
   - **Türkçe Alanlarda Çift Çizgiyi Engelleme:** Zemberek bir metin kutusuna bağlandığında elemanın orijinal `spellcheck` özniteliğini kaydeder. Metin **Türkçe** olarak değerlendirildiği sürece `el.spellcheck = false` ve `data-zmbk-suppress-native="1"` (`::spelling-error, ::grammar-error { text-decoration: none !important }`) uygular. Böylece:
     - Hem Zemberek'in hem Chrome'un aynı kelimenin altını üst üste çizmesi engellenir.
     - Chrome dili yalnızca İngilizce (`en-US`) olan kullanıcılarda geçerli Türkçe kelimelerin Chrome tarafından kırmızı çizilmesi önlenir.
   - **Yabancı Dilde veya Duraklatmada Otomatik Devretme:** Metin kutusu **Türkçe dışı** algılandığı anda (`skippedNonTurkish = true`), kullanıcı *Sayfa içi denetim aktif* anahtarını kapattığında veya ayarlardan bu seçeneği devre dışı bıraktığında Zemberek orijinal `spellcheck` değerini (`spellcheck="true"`) anında geri yükler; böylece Chrome'un kendi İngilizce/Almanca yazım denetleyicisi kesintisiz devreye girer.
2. **Chrome Çok Dilli Yerleşik Sözlükler (`chrome://settings/languages`) ile Birlikte Çalışma:**
   - Chrome ayarlarında hem İngilizce hem Türkçe (`tr-TR` Hunspell) açık olsa bile Türkçe metin kutularında Zemberek bağlamsal denetimi (ayrı/bitişik `-de/-da`, `mi`, `ki`, deasciify ve cümle içi normalizasyon) üstlenir; İngilizce kutularda ise otomatik çekilerek alanı Chrome'un yerleşik sözlüğüne bırakır.
   - Canvas tabanlı editörlerde (ör. Google Docs ana tuvali) Zemberek sayfa içi DOM müdahalesi yapmaz; bu alanlarda Yan Panel Yazım Stüdyosu kullanılır.
3. **Üçüncü Parti Yazım Eklentileri (Grammarly, LanguageTool vb.) ve Kod Editörleri:**
   - **Kod Editörleri Hariçtir:** `.monaco-editor`, `.cm-editor`, `.CodeMirror` ve `.ace_editor` bileşenlerine hiçbir şekilde bağlanılmaz; kaynak kodlar çizilmez.
   - **Sıfır DOM Bozulması:** Zemberek, `contenteditable` alanlarda DOM'a span eklemeyen `CSS.highlights` API'sini, `<textarea>`/`<input>` alanlarda ise izole Shadow-DOM ayna katmanını kullanır.
   - İngilizce metinlerde Zemberek hiçbir alt çizgi üretmediği için İngilizce odaklı eklentilerle (ör. Grammarly) çakışmaz. Türkçe metinlerde ise `spellcheck="false"` sinyaline uymayıp kendi özel katmanını çizen başka bir eklenti açıksa, ilgili sitede diğer eklentiyi kapatmak görsel çakışmayı önler.
4. **Özel Sözlük Sınırı (`Özel Sözlük` vs. Chrome `Custom Dictionary.txt`):**
   - Chrome WebExtension güvenlik modeli eklentilerin tarayıcının dahili `Custom Dictionary.txt` dosyasına erişmesine izin vermez.
   - Zemberek'e eklediğiniz kelimeler `chrome.storage.sync` (80 KB üzeri sözlüklerde otomatik `chrome.storage.local` taşma desteği) üzerinde saklanır ve Wasm çalışma zamanındaki `DynamicStemTrie` yapısına eklenir; böylece eklediğiniz özel isimler tüm Türkçe çekim ve kesme ekleriyle (`Gökbörü` → `Gökbörü'de`, `Gökbörü'nün`) otomatik tanınır.

---

## 4. Türkçe / Yabancı Dil ve Karışık Metin Davranışı

`Türkçe olmayan metinlerde önerileri gizle` (`ignoreNonTurkish = true` varsayılan) ayarı açıkken, tümü cihaz üzerinde çalışan **3 katmanlı yerel dil kapısı** uygulanır (hiçbir metin dış sunucuya gönderilmez):

1. **Katman 1 — İstemci Ön Elemesi (`content-script.ts`):**
   - Düzenlenen elemanın kendi alt ağacındaki açık `lang` özniteliğini (`<textarea lang="en">`, `<div contenteditable lang="de">`) kontrol eder (Türkçe karakter `ç, ğ, ı, İ, ö, ş, ü` yoksa). GitHub, Gmail, Notion ve Slack gibi siteler sayfa kökünde `<html lang="en">` kullandığı için kök `<html>`/`<body>` etiketleri bilerek yok sayılır; yalnızca editör bileşeninin açık dil etiketi dikkate alınır.
   - `>= 35` karakterlik ASCII cümlelerde Chrome'un yerleşik cihaz içi `LanguageDetector` API'si ve `chrome.i18n.detectLanguage` (yerel CLD3 modeli) çalıştırılır; `>= %94` güvenle yabancı dil bulunan cümleler işaretlenir. Çok cümleli paragraflar yalnızca **tüm cümleler** yabancı dilse bu katmanda erken atlanır.
2. **Katman 2 — Ortografik İpuçları ve Eşsesli Korumalı Yabancı İşlev Kelimeleri (`language-gate.ts`):**
   - Türkçe dışı alfabe/aksanlar (`ä, ß, é, è, ñ, å`, Kiril, Yunanca vb.), küçük harfli `w`/`q`/`x` kelimeleri, üretken İngilizce ekler (`-tion`, `-sion`, `-ing`, `-ght`) ve ~160 kesin yabancı işlev kelimesi (`the`, `with`, `please`, `should`, `nicht`, `avec` vb.) yabancı dil sinyali olarak değerlendirilir.
   - **ASCII Türkçe Eşsesli Koruması:** Hem yabancı dil listelerinde hem de Türkçe veya ASCII klavyeyle yazılmış Türkçede geçerli olan kelimeler (`once` [`önce`], `sans` [`şans`], `is` [`iş`], `in`, `on`, `at`, `her`, `his`, `not`, `ben`, `sen`, `var`, `bir`, `dans`, `para`, `son`) yabancı kelime listesinden özellikle çıkarılmıştır; böylece `Daha once sans eseri bulduk` gibi ASCII Türkçe cümleler yanlışlıkla İngilizce/Fransızca sayılmaz.
3. **Katman 3 — Cümle Bazlı Wasm `Deasciify` + Biçimbilimsel Çözümleme Oranı (`wasm-worker.ts`):**
   - Paragraflar UTF-16 cümle sınırlarına ayrılır ve **her cümle bağımsız** değerlendirilir:
     - Belirsiz ASCII cümleler önce Wasm içinde `LatticeDeasciifier` ile Türkçe karakterlerine dönüştürülür (`Bugun toplanti saat ucte baslayacak` → `Bugün toplantı saat üçte başlayacak`) ve `morph_informal` ile çözümlenir.
     - Alfabetik kelimelerinin **`>= %34`'ü** geçerli Türkçe biçimbilimsel çözümlemeye sahip olan cümleler (veya 1–2 kelimelik `>= 5` harfli kısa girdilerde `normalize()` sonucu bilinen bir Türkçe kelimeye dönüşen tekil yazım hataları, ör. `konrol` → `kontrol`) Türkçe kabul edilir.

### Özet Davranış Tablosu

| Metin Kutusu İçeriği | Zemberek Davranışı | Chrome Yerleşik Yazım Denetimi |
| :--- | :--- | :--- |
| **Tam Türkçe** (standart veya ASCII klavye / yazım hatalı) | Tüm yazım, ek (`-de/-da`, `mi`) ve karakter hatalarını renkli çizer | Gizlenir (`spellcheck="false"`, çift çizgi oluşmaz) |
| **Tam Yabancı Dil** (İngilizce, Almanca, Fransızca vb.) | Otomatik devre dışı kalır (`0` öneri) | Açık kalır (`spellcheck="true"`, İngilizce vb. denetlenir) |
| **Karışık Paragraf** (Türkçe + İngilizce cümle bir arada) | **Yalnızca Türkçe cümleleri** denetler; İngilizce cümlelere dokunmaz | Kutu Türkçe içerdiği için çift çizgiyi önlemek adına bastırılır |
| **Kod Editörleri** (`Monaco`, `CodeMirror`, `Ace`) | Tamamen yok sayılır | Editörün kendi ayarı korunur |
