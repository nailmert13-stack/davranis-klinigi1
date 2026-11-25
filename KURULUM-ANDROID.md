# Davranış Kliniği: Android uygulaması kurulumu

Uygulama aynı formu tablette açar ve iki şey ekler:

- **El yazısı tanıma:** Google ML Kit ile tabletin içinde, internetsiz ve ücretsiz çalışır.
- **Görüşme sırasında sesle doldurma:** Konuşma tabletin konuşma tanıma servisiyle yazıya dökülür. Döküm her 30 saniyede bir Claude'a gönderilir ve boş sorular doldurulur. Ses kaydedilmez, yalnızca yazıya dökülen metin kullanılır.

Kayıt, Drive belgesi ve veri tablosu şimdiki gibi Apps Script üzerinden çalışır.

Toplam süre yaklaşık 30–40 dakika. Bilgisayarda yapılacak işler 1–4, tablette yapılacaklar 5–6.

---

## 1. Apps Script'i güncelle

1. script.google.com'da projeni aç.
2. Code.gs'in içini tamamen sil, paketteki yeni `Code.gs`'i yapıştır.
3. Index dosyasının içini tamamen sil, paketteki yeni `Index.html`'i yapıştır.
4. Kaydet.
5. **Dağıt → Dağıtımları yönet** menüsüne gir, mevcut dağıtımın kalemine bas, **Sürüm: Yeni sürüm** seç ve **Dağıt**'a tıkla.

## 2. Uygulama anahtarını oluştur

1. Düzenleyicinin üstündeki fonksiyon listesinden **anahtarOlustur**'u seç ve **Çalıştır**'a bas.
2. Alttaki **Yürütme günlüğü**'nde "Uygulama anahtarı: …" satırı çıkar. Bu anahtarı kopyala, 5. adımda tablete gireceksin.

Anahtar Proje Ayarları → Komut dosyası özellikleri altında `UYGULAMA_ANAHTARI` olarak da saklanır. Anahtarı değiştirmek istersen fonksiyonu tekrar çalıştırman yeterli; bu durumda tabletteki anahtarı da güncellemen gerekir.

## 3. Claude API anahtarını ekle (sesle doldurma için gerekli)

1. https://console.anthropic.com adresinde hesap aç, **Billing** bölümünden kredi yükle.
2. **API Keys**'ten bir anahtar oluştur.
3. Apps Script'te **Proje Ayarları → Komut dosyası özellikleri**'ne `ANTHROPIC_API_KEY` adıyla ekle. Bu anahtar daha önce eklendiyse yeniden eklemene gerek yok.

Sesle doldurma canlıda hızlı ve ucuz bir model kullanır (`claude-haiku-5-5`). Görüşme bitince bütün döküm daha güçlü bir modelle (`claude-sonnet-5-5`) bir kez daha taranır. Modelleri `MODEL_SES` ve `MODEL` özellikleriyle değiştirebilirsin. El yazısı artık tablette tanındığı için bunun için ücret ödenmez.

## 4. Uygulama için ikinci dağıtımı aç

Tarayıcıda kullandığın dağıtım "Yalnızca ben" erişimli. Uygulama Google girişi yapamadığı için ona ayrı bir dağıtım gerekiyor:

1. **Dağıt → Yeni dağıtım → Web uygulaması**'nı seç.
2. Açıklamaya "Android uygulaması" yaz.
3. **Şu kullanıcı olarak yürüt:** Ben. **Erişimi olanlar:** Herkes.
4. **Dağıt**'a bas ve çıkan, sonu `/exec` ile biten adresi kopyala.

Erişim "Herkes" olsa da güvenlik anahtar ile sağlanıyor: 2. adımdaki anahtar olmadan bu adres ne kayıt yapar ne de liste verir, tarayıcıda açılan form da anahtarsız açılmaz. Adresi ve anahtarı kimseyle paylaşma.

## 5. APK'yı oluştur

APK, tablete kurulan uygulama dosyası. İki yol var; birini seç.

### Yol A: GitHub ile (bilgisayara bir şey kurmadan)

1. https://github.com adresinde ücretsiz hesap aç.
2. **New repository** ile yeni bir depo aç: adı `davranis-klinigi`, türü **Private** olsun.
3. Depo sayfasında **uploading an existing file** bağlantısına tıkla.
4. Paketteki `android` klasörünün **içindekileri** (app, gradle, .github, build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew, gradlew.bat) sürükleyip bırak ve **Commit changes**'e bas.
   - `.github` klasörü Mac'te gizli olabilir: Finder'da Cmd + Shift + . ile görünür yap. Windows'ta Görünüm → Gizli öğeler'i aç.
5. Üstteki **Actions** sekmesine geç. "APK derle" kendiliğinden çalışmaya başlar ve 5–10 dakika sürer. Başlamadıysa "APK derle"yi seçip **Run workflow**'a bas.
6. Çalışma yeşil tik alınca içine gir. Alttaki **Artifacts** bölümünden **DavranisKlinigi-APK**'yı indir. İndirilen zipin içinde `app-debug.apk` var.

Çalışma kırmızı çarpıyla biterse, hata satırlarını kopyalayıp Claude'a gönder; düzeltmesi kolay.

### Yol B: Android Studio ile

1. https://developer.android.com/studio adresinden Android Studio'yu kur.
2. **Open** ile paketteki `android` klasörünü aç. İlk açılışta gerekli dosyalar indirileceği için birkaç dakika bekle.
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)** menüsüne gir. Bitince çıkan bildirimde **locate**'e bas; `app-debug.apk` orada.

## 6. Tablete kur ve ayarla

1. `app-debug.apk` dosyasını tablete gönder (e-posta, Drive ya da USB).
2. Tablette dosyaya dokun. "Bilinmeyen kaynaklardan yükleme" izni sorulursa o uygulama için izin ver, sonra **Yükle**'ye bas. Play Protect uyarı verirse **Yine de yükle**'yi seç. Uygulama Play Store'dan gelmediği için bu uyarı normal.
3. **Davranış Kliniği** uygulamasını aç ve üstteki **Ayarlar**'a dokun.
   - **Adres:** 4. adımda kopyaladığın `/exec` ile biten adres.
   - **Anahtar:** 2. adımdaki uygulama anahtarı.
   - **Kaydet**'e bas. Altta son kayıtlar listelenirse bağlantı tamam demektir.
4. **El yazısı:** İlk kez bir alana yazdığında Türkçe el yazısı modeli bir kereye mahsus indirilir (yaklaşık 20 MB). Bunun için internet gerekir; sonrasında internetsiz çalışır.
5. **Ses:** İlk kez "Görüşmeyi dinle"ye bastığında mikrofon izni istenir, izin ver.
   - Samsung tabletlerde **Ayarlar**'da "konuşma tanıma" ya da "sesli giriş" araması yapıp varsayılan servis olarak **Google**'ı seç. Google uygulamasının güncel olduğundan emin ol. Türkçe en iyi bu serviste çalışır.

## Görüşme sırasında kullanım

1. Formu aç ve **Görüşmeyi dinle**'ye bas.
2. Hasta sahibinden onay al ve **Onay alındı, başlat** de. Üstte kırmızı nokta, süre ve konuşmanın döküm satırı görünür.
3. Soruları sor. Her 30 saniyede bir, cevabı geçen boş sorular kendiliğinden dolar:
   - Sesten dolan alanların solunda **sarı çizgi** olur ve altında konuşmadan kısa bir **alıntı** görünür.
   - Yanlış dolan bir alanı **Kaldır** ile boşaltabilirsin.
   - Senin kalemle yazdığın ya da kendin seçtiğin alanlara ses dokunmaz.
4. Görüşme bitince **Kaydı bitir**'e bas. Bütün döküm bir kez daha taranır ve kalan boş sorular doldurulur.
5. Sarı alanları gözden geçir, sonra **Drive'a kaydet**'e bas. Drive belgesinin sonuna görüşme dökümü de eklenir.

**Daha doğru sonuç için:** Soruları formdaki sırayla sor ve cevabı kısaca tekrar et ("yani günde iki kez, tamam"). Tablet ikinizin ortasında dursun.

## Bilinmesi gerekenler

- **Konuşmacı ayrımı yok:** Döküm senin ve sahibin sözlerini ayırmıyor. Cevapları sahibin söylediği gibi tekrar etmen bu yüzden önemli.
- **Kısa sessizlikler:** Android'in konuşma tanıması kısa sessizliklerde durup kendiliğinden yeniden başlıyor. Bazı tabletlerde bu sırada kısa bir bip sesi duyulabilir.
- **Sesle doldurulmayan alanlar:** İmzalar, KVKK onayı, kroki ve etiket fotoğrafı sesle doldurulmaz; onam bölümü bilerek dışarıda bırakıldı.
- **Verinin nereye gittiği:**
  - El yazısı tanıma tamamen tablette kalır.
  - Konuşmanın yazıya dökülmesi, tabletin servisine bağlı olarak Google'ın sunucularında yapılabilir.
  - Dökümden cevap çıkarma Claude API'ye gider.
  - Bu üçünü fakültenin KVKK değerlendirmesine dahil et.
- **İlk derleme:** Uygulamayı burada derleyip deneyemedim. İlk derlemede küçük bir hata çıkarsa, hata çıktısını gönderdiğinde düzeltirim.
