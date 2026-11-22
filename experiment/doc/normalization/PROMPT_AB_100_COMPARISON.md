# 100-Sentence A/B Comparison: Over-Guided Prompt (`v3.1`) vs. Lean Principle Prompt (`v4-lean`)

- **Model & Settings (Identical for Both)**: `gemini-4-hydrogen`, `thinkingLevel = HIGH`, `group_size = 25` (`4` Batch API requests for `100` sentences).
- **System Prompt Length**: `v3.1` = **`6,295` chars** (`43` lines, `85+` word-pair examples) → `v4-lean` = **`2,020` chars** (`18` lines, **`-67.9%` shorter**).
- **Completeness & Retries (`v4-lean`)**: **`1350/1350` tokens (`100%`)**, `0` fallbacks, `0` retries.
- **Token Usage (`v4-lean`, `100` sentences)**: Prompt = `16,299` (`163.0`/sent vs. `244.9`/sent in `v3.1`), Output = `11,999`, Thinking = `23,656`, Total = **`51,954`**.

## 1. Agreement & Difference Summary (`N = 100` Stratified Sentences)

| Metric | Count / Rate |
| :--- | ---: |
| **Exact Cased Sentence Match (`v3.1 == v4-lean`)** | **`78 / 100` (`78%`)** |
| **Case-Insensitive Sentence Match (`tr_lower` identical)** | **`92 / 100` (`92%`)** |
| **Casing-Only Sentence Differences (`CASE_DIFF`)** | **`14 / 100`** |
| **Lexical / Boundary Sentence Differences (`LEX_DIFF`)** | **`8 / 100`** |
| **Exact Token-Slot Match** | **`1,316 / 1,350` (`97.48%`)** |
| **Case-Insensitive Token-Slot Match** | **`1,341 / 1,350` (`99.33%`)** |

---

## 2. All `22` Sentences Where `Old (v3.1)` and `Lean (v4)` Differed

### #2 (`gen_te_000870` — `1_ALL_CAPS` — **CASE_DIFF**)
- **RAW**: `@USER YA BU İNSANLAR NEDEN ÜNLÜYMÜŞ GİBİ FOTO ÇEKİYO ANLAMADIM NEYSE ADMİN BU ÇÖPÜ BİR DAHA PAYLAŞMA`
- **OLD (`v3.1`)**: `@USER Ya bu insanlar neden ünlüymüş gibi foto çekiyor anlamadım neyse admin bu çöpü bir daha paylaşma`
- **LEAN (`v4`)**: `@USER ya bu insanlar neden ünlüymüş gibi foto çekiyor anlamadım neyse admin bu çöpü bir daha paylaşma`
- **Token Diffs**: `#1 YA`: `Ya` → `ya`

### #3 (`gen_te_001742` — `1_ALL_CAPS` — **LEX_DIFF**)
- **RAW**: `KEŞKE ALİMİZDEN DAHA FAZLA SI GELSEE NE YAPALIM CLİK LEYELİM ABİ BOL BOL İNŞALLAH YARDIMIZI DOKUNUR.`
- **OLD (`v3.1`)**: `Keşke elimizden daha fazlası gelse ne yapalım clickleyelim abi bol bol inşallah yardımımız dokunur.`
- **LEAN (`v4`)**: `Keşke elimizden daha fazlası gelse ne yapalım clikleyelim abi bol bol inşallah yardımımız dokunur.`
- **Token Diffs**: `#8 CLİK`: `clickleyelim` → `clikleyelim`

### #9 (`gen_te_001202` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `merhaba mahşude tabiki tanışabiliriz.ben istanbul da oturuyorum:)`
- **OLD (`v3.1`)**: `merhaba Mahşude tabii ki tanışabiliriz. ben İstanbul'da oturuyorum :)`
- **LEAN (`v4`)**: `Merhaba Mahşude tabii ki tanışabiliriz. Ben İstanbul'da oturuyorum :)`
- **Token Diffs**: `#0 merhaba`: `merhaba` → `Merhaba`; `#5 ben`: `ben` → `Ben`

### #10 (`gen_te_003853` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `ya varya eğer yerin temiz olduğunu bilsem uzanıp kahkaha atacam:) Allah neşeni bozmasın Erhan arkadaşım:) SEVGİ: ASIL KUDRETİN SAHİBİDİR www.sevdazanca.tr. cc`
- **OLD (`v3.1`)**: `ya var ya eğer yerin temiz olduğunu bilsem uzanıp kahkaha atacağım :) Allah neşeni bozmasın Erhan arkadaşım :) Sevgi: asıl kudretin sahibidir www.sevdazanca.tr. cc`
- **LEAN (`v4`)**: `Ya var ya eğer yerin temiz olduğunu bilsem uzanıp kahkaha atacağım :) Allah neşeni bozmasın Erhan arkadaşım :) Sevgi: asıl kudretin sahibidir www.sevdazanca.tr. cc`
- **Token Diffs**: `#0 ya`: `ya` → `Ya`

### #11 (`gen_te_002053` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `evet bide öyle bakarızz.. ben uyuyum artık iyi gecelerr canımm ŞenizŞeniz`
- **OLD (`v3.1`)**: `evet bir de öyle bakarız.. ben uyuyayım artık iyi geceler canım Şeniz Şeniz`
- **LEAN (`v4`)**: `Evet bir de öyle bakarız.. Ben uyuyayım artık iyi geceler canım Şeniz Şeniz`
- **Token Diffs**: `#0 evet`: `evet` → `Evet`; `#6 ben`: `ben` → `Ben`

### #12 (`gen_te_003546` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `ya benim bir ara yaşadıklarımı ne güsel anlatmış.... gerçekten çok beğendim. ya ağlatmayın bisleriiiiiii...... nası kıyıyolar anlamam`
- **OLD (`v3.1`)**: `ya benim bir ara yaşadıklarımı ne güzel anlatmış.... gerçekten çok beğendim. ya ağlatmayın bizleri...... nasıl kıyıyorlar anlamam`
- **LEAN (`v4`)**: `Ya benim bir ara yaşadıklarımı ne güzel anlatmış.... Gerçekten çok beğendim. Ya ağlatmayın bizleri...... Nasıl kıyıyorlar anlamam`
- **Token Diffs**: `#0 ya`: `ya` → `Ya`; `#10 gerçekten`: `gerçekten` → `Gerçekten`; `#14 ya`: `ya` → `Ya`; `#19 nası`: `nasıl` → `Nasıl`

### #13 (`gen_te_002009` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `5 para etmez bir cihaz sürekli dönüyor.kanal geçişlerinde bekliyor.almanızı tavsiye etmem`
- **OLD (`v3.1`)**: `5 para etmez bir cihaz sürekli dönüyor. kanal geçişlerinde bekliyor. almanızı tavsiye etmem`
- **LEAN (`v4`)**: `5 para etmez bir cihaz sürekli dönüyor. Kanal geçişlerinde bekliyor. Almanızı tavsiye etmem`
- **Token Diffs**: `#8 kanal`: `kanal` → `Kanal`; `#12 almanızı`: `almanızı` → `Almanızı`

### #14 (`gen_te_001008` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `aşk nasıl birşey bilimiyorumki buraya bende yazayım aşk...... gibidir ya çaresizsiniz... yada ÇARE SİZSİNİZ!!!`
- **OLD (`v3.1`)**: `aşk nasıl bir şey bilmiyorum ki buraya ben de yazayım aşk...... gibidir ya çaresizsiniz... ya da çare sizsiniz!!!`
- **LEAN (`v4`)**: `Aşk nasıl bir şey bilmiyorum ki buraya ben de yazayım aşk...... gibidir ya çaresizsiniz... ya da çare sizsiniz!!!`
- **Token Diffs**: `#0 aşk`: `aşk` → `Aşk`

### #15 (`gen_te_001653` — `2_MULTI_SENT_PUNCT` — **LEX_DIFF**)
- **RAW**: `film herşeyıyle dört dörtlük muzuğuyle oyuncularıyla konusuyla.morlon b. harika izlemeyen varsa izlesin bence harıka bı film`
- **OLD (`v3.1`)**: `film her şeyiyle dört dörtlük müziğiyle oyuncularıyla konusuyla. Marlon B. harika izlemeyen varsa izlesin bence harika bir film`
- **LEAN (`v4`)**: `Film her şeyiyle dört dörtlük müziğiyle oyuncularıyla konusuyla. Morlon B. harika izlemeyen varsa izlesin bence harika bir film`
- **Token Diffs**: `#0 film`: `film` → `Film`; `#8 morlon`: `Marlon` → `Morlon`

### #16 (`gen_te_001520` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `fintables'ta karşılaştırmalı grafik görebilmek için pro abonesi olmanız gerekiyor. nedense arkadaş belirtmemiş.edit: telefonda gerekiyormuş pc'de gerekmiyormuş.`
- **OLD (`v3.1`)**: `Fintables'ta karşılaştırmalı grafik görebilmek için pro abonesi olmanız gerekiyor. nedense arkadaş belirtmemiş. edit: telefonda gerekiyormuş PC'de gerekmiyormuş.`
- **LEAN (`v4`)**: `Fintables'ta karşılaştırmalı grafik görebilmek için pro abonesi olmanız gerekiyor. Nedense arkadaş belirtmemiş. Edit: telefonda gerekiyormuş PC'de gerekmiyormuş.`
- **Token Diffs**: `#10 nedense`: `nedense` → `Nedense`; `#14 edit`: `edit` → `Edit`

### #17 (`gen_te_004498` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `neden böyle kalitesiz filmleri vizyona sokarlarki anlamış deiğilim.dvd de çıksaymış keşke.`
- **OLD (`v3.1`)**: `neden böyle kalitesiz filmleri vizyona sokarlar ki anlamış değilim. DVD'de çıksaymış keşke.`
- **LEAN (`v4`)**: `Neden böyle kalitesiz filmleri vizyona sokarlar ki anlamış değilim. DVD'de çıksaymış keşke.`
- **Token Diffs**: `#0 neden`: `neden` → `Neden`

### #19 (`gen_te_003767` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `Haammanin.. unutmamış annemleri yok arkadşaım burdalar daha.. annem uyuyo babam tv izliyo,malum tosbağam gececi, bende bakıvereyim bisizlere didim :)`
- **OLD (`v3.1`)**: `Hamanin.. unutmamış annemleri yok arkadaşım buradalar daha.. annem uyuyor babam TV izliyor, malum tosbağam gececi, ben de bakıvereyim bir sizlere dedim :)`
- **LEAN (`v4`)**: `Hamanin.. unutmamış annemleri yok arkadaşım buradalar daha.. Annem uyuyor babam TV izliyor, malum tosbağam gececi, ben de bakıvereyim bir sizlere dedim :)`
- **Token Diffs**: `#11 annem`: `annem` → `Annem`

### #21 (`gen_te_001293` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `havanda su dövülmeyen sonuclanmasina cok az kalmis olan cinayet.vardır her şeyin bi sebebi.`
- **OLD (`v3.1`)**: `havanda su dövülmeyen sonuçlanmasına çok az kalmış olan cinayet. vardır her şeyin bir sebebi.`
- **LEAN (`v4`)**: `Havanda su dövülmeyen sonuçlanmasına çok az kalmış olan cinayet. Vardır her şeyin bir sebebi.`
- **Token Diffs**: `#0 havanda`: `havanda` → `Havanda`; `#10 vardır`: `vardır` → `Vardır`

### #22 (`gen_te_002570` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `insanlığa az biraz faydamız dokunsun.folding@homecurecoinbitcoin'e faydalı bir alternatif: curecoin`
- **OLD (`v3.1`)**: `insanlığa az biraz faydamız dokunsun. folding @homecurecoinbitcoin'e faydalı bir alternatif: curecoin`
- **LEAN (`v4`)**: `İnsanlığa az biraz faydamız dokunsun. Folding @homecurecoinbitcoin'e faydalı bir alternatif: curecoin`
- **Token Diffs**: `#0 insanlığa`: `insanlığa` → `İnsanlığa`; `#6 folding`: `folding` → `Folding`

### #24 (`gen_te_003477` — `2_MULTI_SENT_PUNCT` — **CASE_DIFF**)
- **RAW**: `yalnış anladım kızım.yanı 5 aydır denıyoruz ama tıık yoooooooook`
- **OLD (`v3.1`)**: `yanlış anladım kızım. yani 5 aydır deniyoruz ama tık yok`
- **LEAN (`v4`)**: `Yanlış anladım kızım. Yani 5 aydır deniyoruz ama tık yok`
- **Token Diffs**: `#0 yalnış`: `yanlış` → `Yanlış`; `#4 yanı`: `yani` → `Yani`

### #29 (`gen_te_004715` — `3_DIMINUTIVE_OR_ORDINAL` — **CASE_DIFF**)
- **RAW**: `1. YiLinis KutLu oLsun Umarim Daha Nice GuseL Yillar Gecirmeq DiLegi iLe Herkese Tesk.`
- **OLD (`v3.1`)**: `1. Yılınız kutlu olsun umarım daha nice güzel yıllar geçirmek dileği ile herkese teşekkürler.`
- **LEAN (`v4`)**: `1. yılınız kutlu olsun umarım daha nice güzel yıllar geçirmek dileği ile herkese teşekkürler.`
- **Token Diffs**: `#1 YiLinis`: `Yılınız` → `yılınız`

### #31 (`gen_te_002949` — `3_DIMINUTIVE_OR_ORDINAL` — **LEX_DIFF**)
- **RAW**: `@USER Abbas bey merhaba Ziya bey ve Hamza beyle görüştüğümüzde 4+2 sisteminde 4.yılın sonunda eş durumu elde edebileceğimizi söylemişlerdi.`
- **OLD (`v3.1`)**: `@USER Abbas Bey merhaba Ziya Bey ve Hamza Bey'le görüştüğümüzde 4+2 sisteminde 4. yılın sonunda eş durumu elde edebileceğimizi söylemişlerdi.`
- **LEAN (`v4`)**: `@USER Abbas Bey merhaba Ziya Bey ve Hamza Bey'le görüştüğümüzde 4 +2 sisteminde 4. yılın sonunda eş durumu elde edebileceğimizi söylemişlerdi.`
- **Token Diffs**: `#10 4`: `4+2` → `4`; `#11 +2`: `` → `+2`

### #36 (`gen_te_004458` — `4_DATE_TITLE_TECH` — **LEX_DIFF**)
- **RAW**: `19 mart 2025 tarihinden bu yana merkez bankasinin kullanabilecegi net dolar rezervinin 14,4 milyar dolara düstügü iddia edilen kriz.`
- **OLD (`v3.1`)**: `19 Mart 2025 tarihinden bu yana Merkez Bankası'nın kullanabileceği net dolar rezervinin 14,4 milyar dolara düştüğü iddia edilen kriz.`
- **LEAN (`v4`)**: `19 Mart 2025 tarihinden bu yana Merkez Bankasının kullanabileceği net dolar rezervinin 14,4 milyar dolara düştüğü iddia edilen kriz.`
- **Token Diffs**: `#7 bankasinin`: `Bankası'nın` → `Bankasının`

### #61 (`gen_te_002263` — `6_PURE_ASCII` — **LEX_DIFF**)
- **RAW**: `cok yavas ayrica, fisi takinca 5dk.`
- **OLD (`v3.1`)**: `çok yavaş ayrıca, fişi takınca 5 dakika.`
- **LEAN (`v4`)**: `çok yavaş ayrıca, fişi takınca 5 dk.`
- **Token Diffs**: `#6 5dk`: `5 dakika` → `5 dk`

### #92 (`gen_te_000524` — `8_LIGHT_MEDIUM_GENERAL` — **LEX_DIFF**)
- **RAW**: `Kutu kola dışarıda 1TL bu mekanlarda 5tl hemen hemen buda en basiti.`
- **OLD (`v3.1`)**: `Kutu kola dışarıda 1 TL bu mekanlarda 5 TL hemen hemen bu da en basiti.`
- **LEAN (`v4`)**: `Kutu kola dışarıda 1 TL bu mekânlarda 5 TL hemen hemen bu da en basiti.`
- **Token Diffs**: `#5 mekanlarda`: `mekanlarda` → `mekânlarda`

### #94 (`gen_te_000920` — `8_LIGHT_MEDIUM_GENERAL` — **LEX_DIFF**)
- **RAW**: `valla 12gb içindeki bakır borudan sıvı nitrojen geçiyor heralde.`
- **OLD (`v3.1`)**: `valla 12 GB içindeki bakır borudan sıvı nitrojen geçiyor herhalde.`
- **LEAN (`v4`)**: `valla 12 GB içindeki bakır borudan sıvı nitrojen geçiyor herhâlde.`
- **Token Diffs**: `#8 heralde`: `herhalde` → `herhâlde`

### #98 (`gen_te_002855` — `8_LIGHT_MEDIUM_GENERAL` — **LEX_DIFF**)
- **RAW**: `kurgusu ve senaryosu zayıf mekanlar güzel bol küfürlü güzel espirileri olan sinemada izlenmesede dvd de izlenir bi cem yılmaz filmi 106`
- **OLD (`v3.1`)**: `kurgusu ve senaryosu zayıf mekanlar güzel bol küfürlü güzel esprileri olan sinemada izlenmese de DVD'de izlenir bir Cem Yılmaz filmi 106`
- **LEAN (`v4`)**: `kurgusu ve senaryosu zayıf mekânlar güzel bol küfürlü güzel esprileri olan sinemada izlenmese de DVD'de izlenir bir Cem Yılmaz filmi 106`
- **Token Diffs**: `#4 mekanlar`: `mekanlar` → `mekânlar`

---

## 3. Full 100-Sentence Input / Output Table (`#1 .. #100`)

| # | Bucket | Status | Raw Input | Old Prompt (`v3.1`) | Lean Prompt (`v4-lean`) |
| --: | :--- | :---: | :--- | :--- | :--- |
| 1 | `1_ALL_CAPS` | `SAME` | AHHH AH KEL KALICAM KEL | Ah ah kel kalacağım kel | Ah ah kel kalacağım kel |
| 2 | `1_ALL_CAPS` | `CASE_DIFF` | @USER YA BU İNSANLAR NEDEN ÜNLÜYMÜŞ GİBİ FOTO ÇEKİYO ANLAMADIM NEYSE ADMİN BU ÇÖPÜ BİR DAHA PAYLAŞMA | @USER Ya bu insanlar neden ünlüymüş gibi foto çekiyor anlamadım neyse admin bu çöpü bir daha paylaşma | @USER ya bu insanlar neden ünlüymüş gibi foto çekiyor anlamadım neyse admin bu çöpü bir daha paylaşma |
| 3 | `1_ALL_CAPS` | `LEX_DIFF` | KEŞKE ALİMİZDEN DAHA FAZLA SI GELSEE NE YAPALIM CLİK LEYELİM ABİ BOL BOL İNŞALLAH YARDIMIZI DOKUNUR. | Keşke elimizden daha fazlası gelse ne yapalım clickleyelim abi bol bol inşallah yardımımız dokunur. | Keşke elimizden daha fazlası gelse ne yapalım clikleyelim abi bol bol inşallah yardımımız dokunur. |
| 4 | `1_ALL_CAPS` | `SAME` | İSTEDİKLERİ KADAR KIVIRSINLAR GIDECEĞİ YER BELLİ DİRENÇ KIRILMIŞ | İstedikleri kadar kıvırsınlar gideceği yer belli direnç kırılmış | İstedikleri kadar kıvırsınlar gideceği yer belli direnç kırılmış |
| 5 | `1_ALL_CAPS` | `SAME` | AH MİLLET KEŞKE BENIM GIBI ANLYIŞLI SEVDİGİNE DÜŞKÜN OLSA KARA ZİNDANIN BEKÇİSİ | Ah millet keşke benim gibi anlayışlı sevdiğine düşkün olsa kara zindanın bekçisi | Ah millet keşke benim gibi anlayışlı sevdiğine düşkün olsa kara zindanın bekçisi |
| 6 | `1_ALL_CAPS` | `SAME` | ALLAH BELANIZI VERSİN! | Allah belanızı versin! | Allah belanızı versin! |
| 7 | `1_ALL_CAPS` | `SAME` | AMACINIZ DOĞRAMAK PARÇALAMAKSA BLENDER ALIN. | Amacınız doğramak parçalamaksa blender alın. | Amacınız doğramak parçalamaksa blender alın. |
| 8 | `1_ALL_CAPS` | `SAME` | ÜRÜN ÇOK KALİTESİZDİ. İADE İŞLEMİMİZDE KABU EDİLMEDİ | Ürün çok kalitesizdi. İade işlemimiz de kabul edilmedi | Ürün çok kalitesizdi. İade işlemimiz de kabul edilmedi |
| 9 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | merhaba mahşude tabiki tanışabiliriz.ben istanbul da oturuyorum:) | merhaba Mahşude tabii ki tanışabiliriz. ben İstanbul'da oturuyorum :) | Merhaba Mahşude tabii ki tanışabiliriz. Ben İstanbul'da oturuyorum :) |
| 10 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | ya varya eğer yerin temiz olduğunu bilsem uzanıp kahkaha atacam:) Allah neşeni bozmasın Erhan arkadaşım:) SEVGİ: ASIL KUDRETİN SAHİBİDİR www.sevdazanca.tr. cc | ya var ya eğer yerin temiz olduğunu bilsem uzanıp kahkaha atacağım :) Allah neşeni bozmasın Erhan arkadaşım :) Sevgi: asıl kudretin sahibidir www.sevdazanca.tr. cc | Ya var ya eğer yerin temiz olduğunu bilsem uzanıp kahkaha atacağım :) Allah neşeni bozmasın Erhan arkadaşım :) Sevgi: asıl kudretin sahibidir www.sevdazanca.tr. cc |
| 11 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | evet bide öyle bakarızz.. ben uyuyum artık iyi gecelerr canımm ŞenizŞeniz | evet bir de öyle bakarız.. ben uyuyayım artık iyi geceler canım Şeniz Şeniz | Evet bir de öyle bakarız.. Ben uyuyayım artık iyi geceler canım Şeniz Şeniz |
| 12 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | ya benim bir ara yaşadıklarımı ne güsel anlatmış.... gerçekten çok beğendim. ya ağlatmayın bisleriiiiiii...... nası kıyıyolar anlamam | ya benim bir ara yaşadıklarımı ne güzel anlatmış.... gerçekten çok beğendim. ya ağlatmayın bizleri...... nasıl kıyıyorlar anlamam | Ya benim bir ara yaşadıklarımı ne güzel anlatmış.... Gerçekten çok beğendim. Ya ağlatmayın bizleri...... Nasıl kıyıyorlar anlamam |
| 13 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | 5 para etmez bir cihaz sürekli dönüyor.kanal geçişlerinde bekliyor.almanızı tavsiye etmem | 5 para etmez bir cihaz sürekli dönüyor. kanal geçişlerinde bekliyor. almanızı tavsiye etmem | 5 para etmez bir cihaz sürekli dönüyor. Kanal geçişlerinde bekliyor. Almanızı tavsiye etmem |
| 14 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | aşk nasıl birşey bilimiyorumki buraya bende yazayım aşk...... gibidir ya çaresizsiniz... yada ÇARE SİZSİNİZ!!! | aşk nasıl bir şey bilmiyorum ki buraya ben de yazayım aşk...... gibidir ya çaresizsiniz... ya da çare sizsiniz!!! | Aşk nasıl bir şey bilmiyorum ki buraya ben de yazayım aşk...... gibidir ya çaresizsiniz... ya da çare sizsiniz!!! |
| 15 | `2_MULTI_SENT_PUNCT` | `LEX_DIFF` | film herşeyıyle dört dörtlük muzuğuyle oyuncularıyla konusuyla.morlon b. harika izlemeyen varsa izlesin bence harıka bı film | film her şeyiyle dört dörtlük müziğiyle oyuncularıyla konusuyla. Marlon B. harika izlemeyen varsa izlesin bence harika bir film | Film her şeyiyle dört dörtlük müziğiyle oyuncularıyla konusuyla. Morlon B. harika izlemeyen varsa izlesin bence harika bir film |
| 16 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | fintables'ta karşılaştırmalı grafik görebilmek için pro abonesi olmanız gerekiyor. nedense arkadaş belirtmemiş.edit: telefonda gerekiyormuş pc'de gerekmiyormuş. | Fintables'ta karşılaştırmalı grafik görebilmek için pro abonesi olmanız gerekiyor. nedense arkadaş belirtmemiş. edit: telefonda gerekiyormuş PC'de gerekmiyormuş. | Fintables'ta karşılaştırmalı grafik görebilmek için pro abonesi olmanız gerekiyor. Nedense arkadaş belirtmemiş. Edit: telefonda gerekiyormuş PC'de gerekmiyormuş. |
| 17 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | neden böyle kalitesiz filmleri vizyona sokarlarki anlamış deiğilim.dvd de çıksaymış keşke. | neden böyle kalitesiz filmleri vizyona sokarlar ki anlamış değilim. DVD'de çıksaymış keşke. | Neden böyle kalitesiz filmleri vizyona sokarlar ki anlamış değilim. DVD'de çıksaymış keşke. |
| 18 | `2_MULTI_SENT_PUNCT` | `SAME` | Çok kullanışlı ve hemen ısınıyor, ısınması için gereksiz vakit harcamıyor insan. Temizlenmesi de bir o kadar kolay . Tavsiye ederim | Çok kullanışlı ve hemen ısınıyor, ısınması için gereksiz vakit harcamıyor insan. Temizlenmesi de bir o kadar kolay. Tavsiye ederim | Çok kullanışlı ve hemen ısınıyor, ısınması için gereksiz vakit harcamıyor insan. Temizlenmesi de bir o kadar kolay. Tavsiye ederim |
| 19 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | Haammanin.. unutmamış annemleri yok arkadşaım burdalar daha.. annem uyuyo babam tv izliyo,malum tosbağam gececi, bende bakıvereyim bisizlere didim :) | Hamanin.. unutmamış annemleri yok arkadaşım buradalar daha.. annem uyuyor babam TV izliyor, malum tosbağam gececi, ben de bakıvereyim bir sizlere dedim :) | Hamanin.. unutmamış annemleri yok arkadaşım buradalar daha.. Annem uyuyor babam TV izliyor, malum tosbağam gececi, ben de bakıvereyim bir sizlere dedim :) |
| 20 | `2_MULTI_SENT_PUNCT` | `SAME` | Link: http://www.microsoftstore.com/store/msstore/html/pbPage. Help_Win7_usbdvd_dwnTool en güzel yolu ben yazmadan arkadaşımız yazmış (: | Link: http://www.microsoftstore.com/store/msstore/html/pbPage. Help_Win7_usbdvd_dwnTool en güzel yolu ben yazmadan arkadaşımız yazmış (: | Link: http://www.microsoftstore.com/store/msstore/html/pbPage. Help_Win7_usbdvd_dwnTool en güzel yolu ben yazmadan arkadaşımız yazmış (: |
| 21 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | havanda su dövülmeyen sonuclanmasina cok az kalmis olan cinayet.vardır her şeyin bi sebebi. | havanda su dövülmeyen sonuçlanmasına çok az kalmış olan cinayet. vardır her şeyin bir sebebi. | Havanda su dövülmeyen sonuçlanmasına çok az kalmış olan cinayet. Vardır her şeyin bir sebebi. |
| 22 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | insanlığa az biraz faydamız dokunsun.folding@homecurecoinbitcoin'e faydalı bir alternatif: curecoin | insanlığa az biraz faydamız dokunsun. folding @homecurecoinbitcoin'e faydalı bir alternatif: curecoin | İnsanlığa az biraz faydamız dokunsun. Folding @homecurecoinbitcoin'e faydalı bir alternatif: curecoin |
| 23 | `2_MULTI_SENT_PUNCT` | `SAME` | 2009 da çıkacak olan yeni Volvo S40. Gördüğümde ağzım açık kaldı. | 2009'da çıkacak olan yeni Volvo S40. Gördüğümde ağzım açık kaldı. | 2009'da çıkacak olan yeni Volvo S40. Gördüğümde ağzım açık kaldı. |
| 24 | `2_MULTI_SENT_PUNCT` | `CASE_DIFF` | yalnış anladım kızım.yanı 5 aydır denıyoruz ama tıık yoooooooook | yanlış anladım kızım. yani 5 aydır deniyoruz ama tık yok | Yanlış anladım kızım. Yani 5 aydır deniyoruz ama tık yok |
| 25 | `3_DIMINUTIVE_OR_ORDINAL` | `SAME` | Onuda kullandım arada ben fayda görmedim hayırlısı olsun M.2. 80+ | Onu da kullandım arada ben fayda görmedim hayırlısı olsun M. 2. 80 + | Onu da kullandım arada ben fayda görmedim hayırlısı olsun M. 2. 80 + |
| 26 | `3_DIMINUTIVE_OR_ORDINAL` | `SAME` | abd dünyada 1. sırada 2. sırada kore var(güney) bu ülkelerinde mi ekonomileri kötü yani. | ABD dünyada 1. sırada 2. sırada Kore var (Güney) bu ülkelerin de mi ekonomileri kötü yani. | ABD dünyada 1. sırada 2. sırada Kore var (Güney) bu ülkelerin de mi ekonomileri kötü yani. |
| 27 | `3_DIMINUTIVE_OR_ORDINAL` | `SAME` | alalı 13.000km yol yaptım ön lastiklerin dış kısımlarında kılal çatlamalr gördüm.. | alalı 13.000 km yol yaptım ön lastiklerin dış kısımlarında kılcal çatlamalar gördüm.. | alalı 13.000 km yol yaptım ön lastiklerin dış kısımlarında kılcal çatlamalar gördüm.. |
| 28 | `3_DIMINUTIVE_OR_ORDINAL` | `SAME` | 3.9 cm lik küçücük bir boyu var. | 3.9 cm'lik küçücük bir boyu var. | 3.9 cm'lik küçücük bir boyu var. |
| 29 | `3_DIMINUTIVE_OR_ORDINAL` | `CASE_DIFF` | 1. YiLinis KutLu oLsun Umarim Daha Nice GuseL Yillar Gecirmeq DiLegi iLe Herkese Tesk. | 1. Yılınız kutlu olsun umarım daha nice güzel yıllar geçirmek dileği ile herkese teşekkürler. | 1. yılınız kutlu olsun umarım daha nice güzel yıllar geçirmek dileği ile herkese teşekkürler. |
| 30 | `3_DIMINUTIVE_OR_ORDINAL` | `SAME` | @USER Kesinlikle hocam 3.5 yaşında kızım var sitenin bahçesinde bile gözümün önünden ayıramıyorum hem benim kızım kimsenin kendisine dokunmasına izin vermez | @USER Kesinlikle hocam 3.5 yaşında kızım var sitenin bahçesinde bile gözümün önünden ayıramıyorum hem benim kızım kimsenin kendisine dokunmasına izin vermez | @USER Kesinlikle hocam 3.5 yaşında kızım var sitenin bahçesinde bile gözümün önünden ayıramıyorum hem benim kızım kimsenin kendisine dokunmasına izin vermez |
| 31 | `3_DIMINUTIVE_OR_ORDINAL` | `LEX_DIFF` | @USER Abbas bey merhaba Ziya bey ve Hamza beyle görüştüğümüzde 4+2 sisteminde 4.yılın sonunda eş durumu elde edebileceğimizi söylemişlerdi. | @USER Abbas Bey merhaba Ziya Bey ve Hamza Bey'le görüştüğümüzde 4+2 sisteminde 4. yılın sonunda eş durumu elde edebileceğimizi söylemişlerdi. | @USER Abbas Bey merhaba Ziya Bey ve Hamza Bey'le görüştüğümüzde 4 +2 sisteminde 4. yılın sonunda eş durumu elde edebileceğimizi söylemişlerdi. |
| 32 | `3_DIMINUTIVE_OR_ORDINAL` | `SAME` | 9. bölüm Mahir in iş yerinde kariyeri için yeni bir fırsat doğar. | 9. bölüm Mahir'in iş yerinde kariyeri için yeni bir fırsat doğar. | 9. bölüm Mahir'in iş yerinde kariyeri için yeni bir fırsat doğar. |
| 33 | `3_DIMINUTIVE_OR_ORDINAL` | `SAME` | biz domain 2. sayfada diziler yazdığımızda. | biz domain 2. sayfada diziler yazdığımızda. | biz domain 2. sayfada diziler yazdığımızda. |
| 34 | `3_DIMINUTIVE_OR_ORDINAL` | `SAME` | Meltemcim seninde tellerin cıktıgında bekliyoruzz resimleri :) | Meltemciğim senin de tellerin çıktığında bekliyoruz resimleri :) | Meltemciğim senin de tellerin çıktığında bekliyoruz resimleri :) |
| 35 | `4_DATE_TITLE_TECH` | `SAME` | usernamesi alinmis 4k arkadasli hesabi olanlar iceri baksin | usernamesi alınmış 4K arkadaşlı hesabı olanlar içeri baksın | usernamesi alınmış 4K arkadaşlı hesabı olanlar içeri baksın |
| 36 | `4_DATE_TITLE_TECH` | `LEX_DIFF` | 19 mart 2025 tarihinden bu yana merkez bankasinin kullanabilecegi net dolar rezervinin 14,4 milyar dolara düstügü iddia edilen kriz. | 19 Mart 2025 tarihinden bu yana Merkez Bankası'nın kullanabileceği net dolar rezervinin 14,4 milyar dolara düştüğü iddia edilen kriz. | 19 Mart 2025 tarihinden bu yana Merkez Bankasının kullanabileceği net dolar rezervinin 14,4 milyar dolara düştüğü iddia edilen kriz. |
| 37 | `4_DATE_TITLE_TECH` | `SAME` | hep iphone kullanıyordum telefon hafızası nedeniyle androide geçtim ve bu telefonda şu ana kadar performans konusunda hiçbir sorun yaşamadım. | hep iPhone kullanıyordum telefon hafızası nedeniyle Android'e geçtim ve bu telefonda şu ana kadar performans konusunda hiçbir sorun yaşamadım. | hep iPhone kullanıyordum telefon hafızası nedeniyle Android'e geçtim ve bu telefonda şu ana kadar performans konusunda hiçbir sorun yaşamadım. |
| 38 | `4_DATE_TITLE_TECH` | `SAME` | Madde 8)Tatil eylül ayında başlayıp haziran ayında bitmektedir. | Madde 8) Tatil eylül ayında başlayıp haziran ayında bitmektedir. | Madde 8) Tatil eylül ayında başlayıp haziran ayında bitmektedir. |
| 39 | `4_DATE_TITLE_TECH` | `SAME` | Meram Belediyesi tarafından Melikşah Mahallesi'nde yapılacak Konya'nın ilk yüzde yüz ekolojik organik semt pazarının temeli yarın (15 Aralık 2011) atılıyor. | Meram Belediyesi tarafından Melikşah Mahallesi'nde yapılacak Konya'nın ilk yüzde yüz ekolojik organik semt pazarının temeli yarın (15 Aralık 2011) atılıyor. | Meram Belediyesi tarafından Melikşah Mahallesi'nde yapılacak Konya'nın ilk yüzde yüz ekolojik organik semt pazarının temeli yarın (15 Aralık 2011) atılıyor. |
| 40 | `4_DATE_TITLE_TECH` | `SAME` | Youtube'da DAVID BISBAL - AVE MARIA olarak tarayın ve karşınıza çıkacak olan eseri dinleyin ve bana hak verin | YouTube'da David Bisbal - Ave Maria olarak tarayın ve karşınıza çıkacak olan eseri dinleyin ve bana hak verin | YouTube'da David Bisbal - Ave Maria olarak tarayın ve karşınıza çıkacak olan eseri dinleyin ve bana hak verin |
| 41 | `4_DATE_TITLE_TECH` | `SAME` | @USER Berna hanım hattımın uploudu çok yavaş kullanamıyorum yardımcı olurmusunuz | @USER Berna Hanım hattımın uploadu çok yavaş kullanamıyorum yardımcı olur musunuz | @USER Berna Hanım hattımın uploadu çok yavaş kullanamıyorum yardımcı olur musunuz |
| 42 | `4_DATE_TITLE_TECH` | `SAME` | Ikıncı denemem persembe '/ kasımda dondurlumus embrıo transferı yapılıcak bayramın ılk gunu test.... | İkinci denemem perşembe '/ kasımda dondurulmuş embriyo transferi yapılacak bayramın ilk günü test.... | İkinci denemem perşembe '/ kasımda dondurulmuş embriyo transferi yapılacak bayramın ilk günü test.... |
| 43 | `4_DATE_TITLE_TECH` | `SAME` | gecdigimiz ocak ayindaacil bi durumdan dolayi annemlerle türkiyeye gitmem egerketi.. | geçtiğimiz ocak ayında acil bir durumdan dolayı annemlerle Türkiye'ye gitmem gerekti.. | geçtiğimiz ocak ayında acil bir durumdan dolayı annemlerle Türkiye'ye gitmem gerekti.. |
| 44 | `4_DATE_TITLE_TECH` | `SAME` | Ekim ayı bounca 00:00 - 07:00 saatLeri arasında 3000 mesaj bedava hakkınız wardır.. | Ekim ayı boyunca 00:00 - 07:00 saatleri arasında 3000 mesaj bedava hakkınız vardır.. | Ekim ayı boyunca 00:00 - 07:00 saatleri arasında 3000 mesaj bedava hakkınız vardır.. |
| 45 | `4_DATE_TITLE_TECH` | `SAME` | youtube.com da da sahte/orjinal kıyaslama videolarıda izlemiş biri olarak.. | youtube.com da sahte/orijinal kıyaslama videoları da izlemiş biri olarak.. | youtube.com da sahte/orijinal kıyaslama videoları da izlemiş biri olarak.. |
| 46 | `4_DATE_TITLE_TECH` | `SAME` | Telefonu 2017 yılı Şubat ayında almıştım. | Telefonu 2017 yılı Şubat ayında almıştım. | Telefonu 2017 yılı Şubat ayında almıştım. |
| 47 | `5_CLEAN_CONTROL` | `SAME` | Hesabımda sıfır lira olmasına rağmen karşıdaki şirket 13 ay boyunca aylık sunucu bedelini hesabımdan çekmiş. | Hesabımda sıfır lira olmasına rağmen karşıdaki şirket 13 ay boyunca aylık sunucu bedelini hesabımdan çekmiş. | Hesabımda sıfır lira olmasına rağmen karşıdaki şirket 13 ay boyunca aylık sunucu bedelini hesabımdan çekmiş. |
| 48 | `5_CLEAN_CONTROL` | `SAME` | düzeyleri yerlerde gezen "kamu/public/basın/celebrity" insanlarının düzeylerinin gerçekte de yerlerde gezdiğine şahit olmamı sağlayan aparat. | düzeyleri yerlerde gezen "kamu/public/basın/celebrity" insanlarının düzeylerinin gerçekte de yerlerde gezdiğine şahit olmamı sağlayan aparat. | düzeyleri yerlerde gezen "kamu/public/basın/celebrity" insanlarının düzeylerinin gerçekte de yerlerde gezdiğine şahit olmamı sağlayan aparat. |
| 49 | `5_CLEAN_CONTROL` | `SAME` | Makina 9 kilo yazıyor ama sadece pamuklu programda diğer bütün eşyalar sadece 2 yada 3 kilo koyabilirsiniz. | Makine 9 kilo yazıyor ama sadece pamuklu programda diğer bütün eşyalar sadece 2 ya da 3 kilo koyabilirsiniz. | Makine 9 kilo yazıyor ama sadece pamuklu programda diğer bütün eşyalar sadece 2 ya da 3 kilo koyabilirsiniz. |
| 50 | `5_CLEAN_CONTROL` | `SAME` | kamu bankalarının ev kredi faizlerinde yaptıkları indirim sonucunda istisnasız her ev minimum 50000 lira pahalandı. | kamu bankalarının ev kredi faizlerinde yaptıkları indirim sonucunda istisnasız her ev minimum 50000 lira pahalandı. | kamu bankalarının ev kredi faizlerinde yaptıkları indirim sonucunda istisnasız her ev minimum 50000 lira pahalandı. |
| 51 | `5_CLEAN_CONTROL` | `SAME` | Onun için bundan sonra bir yere giderken sinyal verip gitmek de fayda var :) | Onun için bundan sonra bir yere giderken sinyal verip gitmekte fayda var :) | Onun için bundan sonra bir yere giderken sinyal verip gitmekte fayda var :) |
| 52 | `5_CLEAN_CONTROL` | `SAME` | Mamak Belediyesi Belediye Yerinde Güvercin Beslemek Mahallemizde Mamak Belediyesine ait arsalarda güvercin uçuruyorlar. | Mamak Belediyesi Belediye Yerinde Güvercin Beslemek Mahallemizde Mamak Belediyesine ait arsalarda güvercin uçuruyorlar. | Mamak Belediyesi Belediye Yerinde Güvercin Beslemek Mahallemizde Mamak Belediyesine ait arsalarda güvercin uçuruyorlar. |
| 53 | `5_CLEAN_CONTROL` | `SAME` | Beşiktaş Cola Turka'nın 1-0 önde başladığı seride, 2 maçı kazanan Efes Pilsen 2-1 öne geçmişti. | Beşiktaş Cola Turka'nın 1-0 önde başladığı seride, 2 maçı kazanan Efes Pilsen 2-1 öne geçmişti. | Beşiktaş Cola Turka'nın 1-0 önde başladığı seride, 2 maçı kazanan Efes Pilsen 2-1 öne geçmişti. |
| 54 | `5_CLEAN_CONTROL` | `SAME` | Chicco mama saklama kabını hem evde hem de dışarıda güvenle kullanabilirsiniz | Chicco mama saklama kabını hem evde hem de dışarıda güvenle kullanabilirsiniz | Chicco mama saklama kabını hem evde hem de dışarıda güvenle kullanabilirsiniz |
| 55 | `5_CLEAN_CONTROL` | `SAME` | Ekte verdiğim ekran fotoğrafında da göründüğü gibi oyunu indirmek istediğimde bu hatayı alıyorum. | Ekte verdiğim ekran fotoğrafında da göründüğü gibi oyunu indirmek istediğimde bu hatayı alıyorum. | Ekte verdiğim ekran fotoğrafında da göründüğü gibi oyunu indirmek istediğimde bu hatayı alıyorum. |
| 56 | `5_CLEAN_CONTROL` | `SAME` | kilosu çok geride değil bizde 10 aylıkken 8700 idik ama erkek bebeğe göre bizimde düşük tabi. | kilosu çok geride değil biz de 10 aylıkken 8700 idik ama erkek bebeğe göre bizim de düşük tabii. | kilosu çok geride değil biz de 10 aylıkken 8700 idik ama erkek bebeğe göre bizim de düşük tabii. |
| 57 | `5_CLEAN_CONTROL` | `SAME` | şu anda henüz maç oynanmadı bu teknolojide hala idman safhasındayız. | şu anda henüz maç oynanmadı bu teknolojide hâlâ idman safhasındayız. | şu anda henüz maç oynanmadı bu teknolojide hâlâ idman safhasındayız. |
| 58 | `5_CLEAN_CONTROL` | `SAME` | ürünler trafik kazasına uğramış sanki..! | ürünler trafik kazasına uğramış sanki..! | ürünler trafik kazasına uğramış sanki..! |
| 59 | `5_CLEAN_CONTROL` | `SAME` | Ancak sınav sonrası tercih uygulamalarında belirlenecek tarihlerde veya ÖSYM'nin belirteceği şekilde bunun da mümkün olacağını düşünüyoruz. | Ancak sınav sonrası tercih uygulamalarında belirlenecek tarihlerde veya ÖSYM'nin belirteceği şekilde bunun da mümkün olacağını düşünüyoruz. | Ancak sınav sonrası tercih uygulamalarında belirlenecek tarihlerde veya ÖSYM'nin belirteceği şekilde bunun da mümkün olacağını düşünüyoruz. |
| 60 | `5_CLEAN_CONTROL` | `SAME` | İzmirli kızlar güzeldir genelde aslında sana iltifat etmişler :D | İzmirli kızlar güzeldir genelde aslında sana iltifat etmişler :D | İzmirli kızlar güzeldir genelde aslında sana iltifat etmişler :D |
| 61 | `6_PURE_ASCII` | `LEX_DIFF` | cok yavas ayrica, fisi takinca 5dk. | çok yavaş ayrıca, fişi takınca 5 dakika. | çok yavaş ayrıca, fişi takınca 5 dk. |
| 62 | `6_PURE_ASCII` | `SAME` | #Iran'a nasil mudahile edebiliriz demek istemisler | #Iran'a nasıl müdahale edebiliriz demek istemişler | #Iran'a nasıl müdahale edebiliriz demek istemişler |
| 63 | `6_PURE_ASCII` | `SAME` | Prof. den bir sayin hocam anfide anlattiydi.. | Prof.den bir sayın hocam amfide anlattıydı.. | Prof.den bir sayın hocam amfide anlattıydı.. |
| 64 | `6_PURE_ASCII` | `SAME` | Yok maalesef ssd anlarim da anakartin ne olmasini isterdiniz hocam? | Yok maalesef SSD anlarım da anakartın ne olmasını isterdiniz hocam? | Yok maalesef SSD anlarım da anakartın ne olmasını isterdiniz hocam? |
| 65 | `6_PURE_ASCII` | `SAME` | biri size bol keseden limit tanirken digeri sizi kisitlamakla mesgul sanki atomu parcaliyor gibi bir algi yaratmis insanlarda. | biri size bol keseden limit tanırken diğeri sizi kısıtlamakla meşgul sanki atomu parçalıyor gibi bir algı yaratmış insanlarda. | biri size bol keseden limit tanırken diğeri sizi kısıtlamakla meşgul sanki atomu parçalıyor gibi bir algı yaratmış insanlarda. |
| 66 | `6_PURE_ASCII` | `SAME` | tinder resmen dipsiz kuyu gibi surekli nefret ederek acip kapatiyorum yeter artik al canimi da kurtulalim | Tinder resmen dipsiz kuyu gibi sürekli nefret ederek açıp kapatıyorum yeter artık al canımı da kurtulalım | Tinder resmen dipsiz kuyu gibi sürekli nefret ederek açıp kapatıyorum yeter artık al canımı da kurtulalım |
| 67 | `6_PURE_ASCII` | `SAME` | Derdini fazla uzatmadan anlatan, son zamanlarda karsimiza cikan en iyi animasyonlardan biri. | Derdini fazla uzatmadan anlatan, son zamanlarda karşımıza çıkan en iyi animasyonlardan biri. | Derdini fazla uzatmadan anlatan, son zamanlarda karşımıza çıkan en iyi animasyonlardan biri. |
| 68 | `6_PURE_ASCII` | `SAME` | annemler yan katimin ustunde oturuyorlar ve interneti ordan kullaniyorum internette surekli kopma oluyordu ve 2 dis cekiodu. | annemler yan katımın üstünde oturuyorlar ve interneti oradan kullanıyorum internette sürekli kopma oluyordu ve 2 diş çekiyordu. | annemler yan katımın üstünde oturuyorlar ve interneti oradan kullanıyorum internette sürekli kopma oluyordu ve 2 diş çekiyordu. |
| 69 | `6_PURE_ASCII` | `SAME` | eger gecerli bi sebebi yoksa cidden yanlis yapmislar. | eğer geçerli bir sebebi yoksa cidden yanlış yapmışlar. | eğer geçerli bir sebebi yoksa cidden yanlış yapmışlar. |
| 70 | `6_PURE_ASCII` | `SAME` | saga sola bilerek carpiyor ama kasasi saglam. | sağa sola bilerek çarpıyor ama kasası sağlam. | sağa sola bilerek çarpıyor ama kasası sağlam. |
| 71 | `6_PURE_ASCII` | `SAME` | starlink kullanip direk nokta atisi bomba biraksaydi bu kadar etkili olmazdi. | Starlink kullanıp direkt nokta atışı bomba bıraksaydı bu kadar etkili olmazdı. | Starlink kullanıp direkt nokta atışı bomba bıraksaydı bu kadar etkili olmazdı. |
| 72 | `6_PURE_ASCII` | `SAME` | bencede, sahsen bu fiyata beni uzcegini sanmiyorum | bence de, şahsen bu fiyata beni üzeceğini sanmıyorum | bence de, şahsen bu fiyata beni üzeceğini sanmıyorum |
| 73 | `7_HEAVY_SLANG_TYPO` | `SAME` | edge birde ameliyat sonrası gerekli bişi yok sanırım biz hemen çıkıp gidicez terlik-gecelik vb. şeylere ihtiyaç yoktur dimi cnm. | edge bir de ameliyat sonrası gerekli bir şey yok sanırım biz hemen çıkıp gideceğiz terlik-gecelik vb. şeylere ihtiyaç yoktur değil mi canım. | edge bir de ameliyat sonrası gerekli bir şey yok sanırım biz hemen çıkıp gideceğiz terlik-gecelik vb. şeylere ihtiyaç yoktur değil mi canım. |
| 74 | `7_HEAVY_SLANG_TYPO` | `SAME` | Tamamiyle belden aşşağı bi muhhabbet dönüyo hadi dönsün anladık ama komik olan bi espiride yok. | Tamamıyla belden aşağı bir muhabbet dönüyor hadi dönsün anladık ama komik olan bir espri de yok. | Tamamıyla belden aşağı bir muhabbet dönüyor hadi dönsün anladık ama komik olan bir espri de yok. |
| 75 | `7_HEAVY_SLANG_TYPO` | `SAME` | ben bu urunu bır arkadasta gordum pıyasada bulamadıgım urunu hepsıburda da buldum ... | ben bu ürünü bir arkadaşta gördüm piyasada bulamadığım ürünü Hepsiburada'da buldum... | ben bu ürünü bir arkadaşta gördüm piyasada bulamadığım ürünü Hepsiburada'da buldum... |
| 76 | `7_HEAVY_SLANG_TYPO` | `SAME` | ozaman anakartı degiştirirsem oradakilere göre ne onerirsiniz Kırmızı | o zaman anakartı değiştirirsem oradakilere göre ne önerirsiniz Kırmızı | o zaman anakartı değiştirirsem oradakilere göre ne önerirsiniz Kırmızı |
| 77 | `7_HEAVY_SLANG_TYPO` | `SAME` | kızın ismi gharra bostani eid. | kızın ismi Gharra Bostani Eid. | kızın ismi Gharra Bostani Eid. |
| 78 | `7_HEAVY_SLANG_TYPO` | `SAME` | professenel anlamda kullanılırmı o kısmında birşey diğemiyeceim fakat . | profesyonel anlamda kullanılır mı o kısmında bir şey diyemeyeceğim fakat. | profesyonel anlamda kullanılır mı o kısmında bir şey diyemeyeceğim fakat. |
| 79 | `7_HEAVY_SLANG_TYPO` | `SAME` | biliyorum dogum sonrası cok rahatlıyıcam cocugumla ilgilenicem haraket alanım genişliyicek eskisi gibi olucam... | biliyorum doğum sonrası çok rahatlayacağım çocuğumla ilgileneceğim hareket alanım genişleyecek eskisi gibi olacağım... | biliyorum doğum sonrası çok rahatlayacağım çocuğumla ilgileneceğim hareket alanım genişleyecek eskisi gibi olacağım... |
| 80 | `7_HEAVY_SLANG_TYPO` | `SAME` | Cok guzel ve eglencesi bol bır tatıl gecırdım butun departmanlara bu guzel tatıl ıcın tessekkur edıyorum. | Çok güzel ve eğlencesi bol bir tatil geçirdim bütün departmanlara bu güzel tatil için teşekkür ediyorum. | Çok güzel ve eğlencesi bol bir tatil geçirdim bütün departmanlara bu güzel tatil için teşekkür ediyorum. |
| 81 | `7_HEAVY_SLANG_TYPO` | `SAME` | herbırı mukemmel ama en etkılendıgın hangısı ıllah arasından sec dersen küp2 derım ben | her biri mükemmel ama en etkilendiğin hangisi illa arasından seç dersen Küp 2 derim ben | her biri mükemmel ama en etkilendiğin hangisi illa arasından seç dersen Küp 2 derim ben |
| 82 | `7_HEAVY_SLANG_TYPO` | `SAME` | töbe töbee ))) güLermisin ağLarmısın... | tövbe tövbe))) güler misin ağlar mısın... | tövbe tövbe))) güler misin ağlar mısın... |
| 83 | `7_HEAVY_SLANG_TYPO` | `SAME` | Bir >>> > >sirket >>> > > > sahibi bu resimi görmüs, gülmüs ve silmis. | Bir > > > > > şirket > > > > > > sahibi bu resmi görmüş, gülmüş ve silmiş. | Bir > > > > > şirket > > > > > > sahibi bu resmi görmüş, gülmüş ve silmiş. |
| 84 | `7_HEAVY_SLANG_TYPO` | `SAME` | eşim 30 gunu doldugu için suan sıgortalıyız ama nasıl bakcagımı bılmıyorum | eşim 30 günü dolduğu için şu an sigortalıyız ama nasıl bakacağımı bilmiyorum | eşim 30 günü dolduğu için şu an sigortalıyız ama nasıl bakacağımı bilmiyorum |
| 85 | `7_HEAVY_SLANG_TYPO` | `SAME` | makina sorunsuz gayet kullanişli ,satici çok hizli kargoladi teşşekkurler hepsiburada.com | makine sorunsuz gayet kullanışlı, satıcı çok hızlı kargoladı teşekkürler hepsiburada.com | makine sorunsuz gayet kullanışlı, satıcı çok hızlı kargoladı teşekkürler hepsiburada.com |
| 86 | `7_HEAVY_SLANG_TYPO` | `SAME` | hosca vakıt gecırebılecegınız bır fılm | hoşça vakit geçirebileceğiniz bir film | hoşça vakit geçirebileceğiniz bir film |
| 87 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | demediklerini bırakmazlardışu ülkede herşey ama herşey rayından çıktı. | demediklerini bırakmazlardı şu ülkede her şey ama her şey rayından çıktı. | demediklerini bırakmazlardı şu ülkede her şey ama her şey rayından çıktı. |
| 88 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | scooby doo daki hayaletler bile daha korkunçtur | Scooby Doo'daki hayaletler bile daha korkunçtur | Scooby Doo'daki hayaletler bile daha korkunçtur |
| 89 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | açtığım an bluetoothu sürekli 3650 diye bi yerden virüs şeyi gelio kabul etmiyorum ama her dakkada bir geliyodu... | açtığım an Bluetooth'u sürekli 3650 diye bir yerden virüs şeyi geliyor kabul etmiyorum ama her dakikada bir geliyordu... | açtığım an Bluetooth'u sürekli 3650 diye bir yerden virüs şeyi geliyor kabul etmiyorum ama her dakikada bir geliyordu... |
| 90 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | fatih terimin maç sonrası italyanca ropörtajı | Fatih Terim'in maç sonrası İtalyanca röportajı | Fatih Terim'in maç sonrası İtalyanca röportajı |
| 91 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | 5-Taşıma esnasında evin içine zarar verirmiyiz diye hiç dönülmüyor, asma tavanım çöktü resmen.. | 5-Taşıma esnasında evin içine zarar verir miyiz diye hiç dönülmüyor, asma tavanım çöktü resmen.. | 5-Taşıma esnasında evin içine zarar verir miyiz diye hiç dönülmüyor, asma tavanım çöktü resmen.. |
| 92 | `8_LIGHT_MEDIUM_GENERAL` | `LEX_DIFF` | Kutu kola dışarıda 1TL bu mekanlarda 5tl hemen hemen buda en basiti. | Kutu kola dışarıda 1 TL bu mekanlarda 5 TL hemen hemen bu da en basiti. | Kutu kola dışarıda 1 TL bu mekânlarda 5 TL hemen hemen bu da en basiti. |
| 93 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | bunuda dikkate alırsak çok fark var aramızda onlarında bizim zamanımızda yapamayacakları şeyelr de var mesala:=) | bunu da dikkate alırsak çok fark var aramızda onların da bizim zamanımızda yapamayacakları şeyler de var mesela: =) | bunu da dikkate alırsak çok fark var aramızda onların da bizim zamanımızda yapamayacakları şeyler de var mesela: =) |
| 94 | `8_LIGHT_MEDIUM_GENERAL` | `LEX_DIFF` | valla 12gb içindeki bakır borudan sıvı nitrojen geçiyor heralde. | valla 12 GB içindeki bakır borudan sıvı nitrojen geçiyor herhalde. | valla 12 GB içindeki bakır borudan sıvı nitrojen geçiyor herhâlde. |
| 95 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | bu fonlara para yatıracağınıza direk dolara,altına yatırın zarar etmezsiniz | bu fonlara para yatıracağınıza direkt dolara, altına yatırın zarar etmezsiniz | bu fonlara para yatıracağınıza direkt dolara, altına yatırın zarar etmezsiniz |
| 96 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | Absürd,macera-komedi türünde yağmasa da gürleyen,bir önceki işten daha iyi ama Düğün Dernek kadar da güldürmüyor. | Absürt, macera-komedi türünde yağmasa da gürleyen, bir önceki işten daha iyi ama Düğün Dernek kadar da güldürmüyor. | Absürt, macera-komedi türünde yağmasa da gürleyen, bir önceki işten daha iyi ama Düğün Dernek kadar da güldürmüyor. |
| 97 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | herkez onu izliyor.. | herkes onu izliyor.. | herkes onu izliyor.. |
| 98 | `8_LIGHT_MEDIUM_GENERAL` | `LEX_DIFF` | kurgusu ve senaryosu zayıf mekanlar güzel bol küfürlü güzel espirileri olan sinemada izlenmesede dvd de izlenir bi cem yılmaz filmi 106 | kurgusu ve senaryosu zayıf mekanlar güzel bol küfürlü güzel esprileri olan sinemada izlenmese de DVD'de izlenir bir Cem Yılmaz filmi 106 | kurgusu ve senaryosu zayıf mekânlar güzel bol küfürlü güzel esprileri olan sinemada izlenmese de DVD'de izlenir bir Cem Yılmaz filmi 106 |
| 99 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | Anneve bebis link sormak durumunda kalacağım malesef verdiğin bilgiyi anlayabilmek adına. | Anne ve bebiş link sormak durumunda kalacağım maalesef verdiğin bilgiyi anlayabilmek adına. | Anne ve bebiş link sormak durumunda kalacağım maalesef verdiğin bilgiyi anlayabilmek adına. |
| 100 | `8_LIGHT_MEDIUM_GENERAL` | `SAME` | ailesi açık olmasına rrağmen kendi isteğiyle kpandi. | ailesi açık olmasına rağmen kendi isteğiyle kapandı. | ailesi açık olmasına rağmen kendi isteğiyle kapandı. |
