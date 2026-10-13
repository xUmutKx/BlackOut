# LSPosed modül tanıma teşhisi

Tarih: 2026-10-11. Kapsam: LSPosed'in BlackOut modülünü okumaması. Derleme (gradle) çalıştırılmadı.

## Dosya düzeyinde kontrol (hepsi tamam)

| Kontrol | Kaynak | Derlenmiş APK (v0.41) | Durum |
|---|---|---|---|
| `xposedmodule` = true | AndroidManifest.xml | evet (binary manifest'te string olarak var) | tamam |
| `xposeddescription` | AndroidManifest.xml | evet | tamam |
| `xposedminversion` = 93 | AndroidManifest.xml | evet | tamam (eski LSPosed için) |
| `xposedscope` (`@array/xposed_scope`) | AndroidManifest.xml + res/values/arrays.xml | evet | tamam |
| `xposedsharedprefs` = true | AndroidManifest.xml | evet | tamam |
| Legacy giriş `assets/xposed_init` | `com.umutk.blackout.XposedInit` | `assets/xposed_init` aynı içerik | tamam |
| Modern giriş `META-INF/xposed/java_init.list` | `com.umutk.blackout.ModernEntry` | aynı içerik | tamam |
| `META-INF/xposed/module.prop` | minApiVersion=101, targetApiVersion=102, staticScope=false, exceptionMode=protective | aynı içerik (82 bayt, CRLF yok) | tamam |
| `META-INF/xposed/scope.list` | 13 paket, `com.umutk.blackout` dahil | aynı içerik | tamam |
| Giriş sınıfları R8 sonrası korunuyor | proguard-rules.pro: `-keep class com.umutk.blackout.XposedInit`, `-keep class com.umutk.blackout.ModernEntry` | `classes.dex` içinde iki sınıf da var | tamam |
| `ModernEntry` kurucusu | `XposedModule()` public no-arg (api-102.0.0.aar, javap) | — | uyumlu |
| libxposed API `compileOnly` | app/build.gradle.kts | APK'da API sınıfı paketlenmemiş | doğru |
| Birleştirilmiş manifest | `build/intermediates/merged_manifest/release` | xposed meta-data ve HookReceiver var | tamam |

Yani modül tanıma için gereken dosyaların hiçbiri eksik ya da yanlış değil. Metinler satır sonu ve BOM açısından da temiz.

## Kök neden

Paket içinde tanımayı engelleyen bir hata bulunamadı. Kaynak, birleştirilmiş manifest ve v0.41 APK aynı metadata'yı taşıyor. Bu yüzden sorun büyük olasılıkla cihaz tarafında:

1. LSPosed sürümü libxposed API 101/102'yi desteklemiyor olabilir. Bu durumda modern giriş atlanır, ama legacy giriş (`xposedmodule` + `assets/xposed_init`) yine çalışmalı. Modül listede hâlâ görünmüyorsa LSPosed bu paketi hiç taramamış demektir.
2. Modül kurulumu eski bir sürümle kalmış olabilir ya da LSPosed listesi yenilenmemiş olabilir (LSPosed'de modül listesi yeniden tarama ister; cihaz yeniden başlatma ya da LSPosed'i kapatıp açma gerekebilir).
3. Farklı imzalı bir eski kurulum varsa paket güncellenemez ve LSPosed yeni APK'yı görmez. v0.41 debug imzasıyla derleniyor (`signingConfig = debug`), eski kurulum farklı imzalıysa kaldırıp yeniden kurmak gerekir.

Not: `AndroidManifest.xml` içinde `io.github.libxposed.service.XposedProvider` `tools:node="remove"` ile çıkarılmış. Bu, LSPosed'in modül uygulamasına servis bağlantısını taşıyan sağlayıcıdır. Modül tanımayı bu sağlayıcıya bağlamaz, yani bu değişiklik tanıma sorununun sebebi değil. Ama BlackOut'un kendi ayarları (`SettingsMirror`, `XposedServiceHelper`) bu bağlantıya dayanır. Bu, diğer ajanın kapsamında.

## Düzeltme listesi

Kodda yapılacak zorunlu bir düzeltme yok. Aşağıdakiler cihazda sırayla denenmeli:

1. LSPosed sürümünü kontrol et. libxposed API 101/102 destekleyen bir sürüm (yeni LSPosed) olmalı. Eski sürümde yalnızca legacy yol çalışır.
2. BlackOut'u eski imzalı bir kurulumdan kaldırıp v0.41 APK'yı (`/sdcard/Download/projects/apk/BlackOut-v0.41.apk`) yeniden kur.
3. LSPosed → Modules ekranında BlackOut'u aç ve uygulamaları (scope) işaretle. Ardından cihazı yeniden başlat.
4. Hâlâ görünmüyorsa LSPosed günlüğünde (Logs) BlackOut'la ilgili satırları ara; `module.prop`, `java_init.list` ya da `minApiVersion` uyumsuzluğu burada görünür.
5. Kodda değişiklik gerekirse ilk aday `xposedminversion` değeridir, ama 93'ü yükseltmek eski LSPosed'lerde modülü tamamen engeller. Bu yüzden şimdilik dokunulmadı.

## Yapılmayanlar

- gradle çalıştırılmadı; APK yeniden derlenmedi.
- Cihazda LSPosed sürümü ve günlükleri okunmadı (erişim yok).
- Kod değişikliği yapılmadı; metadata doğru olduğu için tahmini bir değişiklik riskli olurdu.
