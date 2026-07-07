package com.winlator.core;

import com.winlator.container.Container;
import com.winlator.core.envvars.EnvVars;

import java.util.Locale;

public final class RuntimeLocaleHelper {
    private RuntimeLocaleHelper() {}

    public static void applyToEnvVars(Container container, EnvVars envVars) {
        if (envVars == null) return;

        LocaleProfile profile = fromContainer(container);
        envVars.put("LANG", profile.locale);
        envVars.put("LANGUAGE", profile.locale);
        envVars.put("LC_ALL", profile.locale);
        envVars.put("LC_CTYPE", profile.locale);
        envVars.put("LC_MESSAGES", profile.locale);

        envVars.put("SteamLanguage", profile.steamLanguage);
        envVars.put("SteamUILanguage", profile.steamLanguage);
        envVars.put("SteamClientLanguage", profile.steamLanguage);
        envVars.put("STEAM_LANGUAGE", profile.steamLanguage);
    }

    public static String localeForContainer(Container container) {
        return fromContainer(container).locale;
    }

    public static String steamLanguageForContainer(Container container) {
        return fromContainer(container).steamLanguage;
    }

    public static String epicLocaleForContainer(Container container) {
        return fromContainer(container).epicLocale;
    }

    public static String installerLanguageForContainer(Container container) {
        return fromContainer(container).installerLanguage;
    }

    private static LocaleProfile fromContainer(Container container) {
        String language = "english";
        if (container != null) {
            String containerLanguage = container.getLanguage();
            if (containerLanguage != null && !containerLanguage.isEmpty()) {
                language = containerLanguage;
            }
        }

        switch (language.toLowerCase(Locale.US)) {
            case "arabic":
                return new LocaleProfile("ar_SA.utf8", "arabic", "ar-SA", "Arabic");
            case "bulgarian":
                return new LocaleProfile("bg_BG.utf8", "bulgarian", "bg-BG", "Bulgarian");
            case "schinese":
                return new LocaleProfile("zh_CN.utf8", "schinese", "zh-CN", "ChineseSimplified");
            case "tchinese":
                return new LocaleProfile("zh_TW.utf8", "tchinese", "zh-TW", "ChineseTraditional");
            case "czech":
                return new LocaleProfile("cs_CZ.utf8", "czech", "cs-CZ", "Czech");
            case "danish":
                return new LocaleProfile("da_DK.utf8", "danish", "da-DK", "Danish");
            case "dutch":
                return new LocaleProfile("nl_NL.utf8", "dutch", "nl-NL", "Dutch");
            case "finnish":
                return new LocaleProfile("fi_FI.utf8", "finnish", "fi-FI", "Finnish");
            case "french":
                return new LocaleProfile("fr_FR.utf8", "french", "fr-FR", "French");
            case "german":
                return new LocaleProfile("de_DE.utf8", "german", "de-DE", "German");
            case "greek":
                return new LocaleProfile("el_GR.utf8", "greek", "el-GR", "Greek");
            case "hungarian":
                return new LocaleProfile("hu_HU.utf8", "hungarian", "hu-HU", "Hungarian");
            case "italian":
                return new LocaleProfile("it_IT.utf8", "italian", "it-IT", "Italian");
            case "japanese":
                return new LocaleProfile("ja_JP.utf8", "japanese", "ja-JP", "Japanese");
            case "koreana":
                return new LocaleProfile("ko_KR.utf8", "koreana", "ko-KR", "Korean");
            case "norwegian":
                return new LocaleProfile("nb_NO.utf8", "norwegian", "nb-NO", "Norwegian");
            case "polish":
                return new LocaleProfile("pl_PL.utf8", "polish", "pl-PL", "Polish");
            case "portuguese":
                return new LocaleProfile("pt_PT.utf8", "portuguese", "pt-PT", "Portuguese");
            case "brazilian":
                return new LocaleProfile("pt_BR.utf8", "brazilian", "pt-BR", "PortugueseBrazilian");
            case "romanian":
                return new LocaleProfile("ro_RO.utf8", "romanian", "ro-RO", "Romanian");
            case "russian":
                return new LocaleProfile("ru_RU.utf8", "russian", "ru-RU", "Russian");
            case "spanish":
                return new LocaleProfile("es_ES.utf8", "spanish", "es-ES", "Spanish");
            case "latam":
                return new LocaleProfile("es_MX.utf8", "latam", "es-MX", "SpanishLatinAmerica");
            case "swedish":
                return new LocaleProfile("sv_SE.utf8", "swedish", "sv-SE", "Swedish");
            case "thai":
                return new LocaleProfile("th_TH.utf8", "thai", "th-TH", "Thai");
            case "turkish":
                return new LocaleProfile("tr_TR.utf8", "turkish", "tr-TR", "Turkish");
            case "ukrainian":
                return new LocaleProfile("uk_UA.utf8", "ukrainian", "uk-UA", "Ukrainian");
            case "vietnamese":
                return new LocaleProfile("vi_VN.utf8", "vietnamese", "vi-VN", "Vietnamese");
            case "english":
            default:
                return new LocaleProfile("en_US.utf8", "english", "en-US", "English");
        }
    }

    private static final class LocaleProfile {
        private final String locale;
        private final String steamLanguage;
        private final String epicLocale;
        private final String installerLanguage;

        private LocaleProfile(String locale, String steamLanguage, String epicLocale, String installerLanguage) {
            this.locale = locale;
            this.steamLanguage = steamLanguage;
            this.epicLocale = epicLocale;
            this.installerLanguage = installerLanguage;
        }
    }
}
