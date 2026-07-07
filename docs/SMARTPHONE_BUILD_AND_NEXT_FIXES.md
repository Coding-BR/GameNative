# GameNative: Build para Smartphone e Proximas Correcoes

## Resumo

Este documento registra o metodo usado para compilar e instalar o GameNative no smartphone de teste, alem do que ainda falta melhorar no runtime/root boost e na correcao de idioma dos jogos.

Smartphone usado nos testes:

- Device ADB: `9125319102E9`
- Plataforma: Android moderno
- Perfil esperado: Snapdragon 8 Elite / aparelho de alta performance
- Variante correta para instalar: **modern universal**
- Variante que nao deve ser usada neste aparelho: **legacy**

## Metodo Correto para Compilar

Executar a partir da raiz do projeto:

```powershell
cd C:\Users\adriano\Desktop\emulador\GameNative
```

Primeiro validar compilacao Kotlin/Java da variante moderna:

```powershell
.\gradlew.bat :app:compileModernDebugKotlin :app:compileModernDebugJavaWithJavac --console=plain
```

Depois gerar o bundle moderno:

```powershell
.\gradlew.bat :app:bundleModernDebug --console=plain
```

O bundle gerado fica em:

```text
C:\Users\adriano\Desktop\emulador\GameNative\app\build\outputs\bundle\modernDebug\app-modern-debug.aab
```

## Metodo para Gerar APK Universal

O smartphone precisa do APK universal moderno, contendo tudo. Usar `bundletool`:

```powershell
$bundle = "app\build\outputs\bundle\modernDebug\app-modern-debug.aab"
$outDir = "app\build\outputs\universal\modernDebug"

New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue "$outDir\app-modern-debug.apks", "$outDir\universal-modern-debug.apk"

java -Xmx2g -jar tools\bundletool-all-1.17.2.jar build-apks --bundle="$bundle" --output="$outDir\app-modern-debug.apks" --mode=universal

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = Resolve-Path "$outDir\app-modern-debug.apks"
$target = (Resolve-Path $outDir).Path + "\universal-modern-debug.apk"
$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
  $entry = $zip.GetEntry("universal.apk")
  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
} finally {
  $zip.Dispose()
}

Get-Item $target | Format-List FullName,Length,LastWriteTime
```

APK final:

```text
C:\Users\adriano\Desktop\emulador\GameNative\app\build\outputs\universal\modernDebug\universal-modern-debug.apk
```

## Metodo para Instalar no Smartphone

Confirmar que o aparelho esta conectado:

```powershell
adb devices
```

Instalar:

```powershell
adb -s 9125319102E9 install -r "C:\Users\adriano\Desktop\emulador\GameNative\app\build\outputs\universal\modernDebug\universal-modern-debug.apk"
```

Liberar acesso total a arquivos, necessario para o app:

```powershell
adb -s 9125319102E9 shell appops set app.gamenative MANAGE_EXTERNAL_STORAGE allow
```

Verificar pacote instalado:

```powershell
adb -s 9125319102E9 shell dumpsys package app.gamenative | Select-String -Pattern "versionCode|versionName|targetSdk|FOREGROUND_SERVICE|MANAGE_EXTERNAL_STORAGE"
```

Resultado esperado:

- `targetSdk=36`
- `FOREGROUND_SERVICE: granted=true`
- `FOREGROUND_SERVICE_DATA_SYNC: granted=true`
- `MANAGE_EXTERNAL_STORAGE` liberado por appops

## Como Verificar se o Jogo Esta Usando Root Boost

Listar processos:

```powershell
adb -s 9125319102E9 shell ps -A | Select-String -Pattern "app.gamenative|GTA|GTAV|wine|wineserver|linker64|box64|fex" -CaseSensitive:$false
```

Verificar processo principal do jogo, trocando o PID pelo PID real do `GTA5.exe`:

```powershell
adb -s 9125319102E9 shell su -c "cat /proc/18857/comm; cat /proc/18857/cpuset; cat /proc/18857/cgroup; cat /proc/18857/oom_score_adj; grep Cpus_allowed_list /proc/18857/status; grep VmRSS /proc/18857/status; grep State /proc/18857/status; grep Threads /proc/18857/status; taskset -p 18857"
```

Resultado desejado:

```text
GTA5.exe
/gamenative
cpu:/gamenative
oom_score_adj=-800
State: R (running)
Threads: alto
VmRSS: alto
```

Verificar servico nativo:

```powershell
adb -s 9125319102E9 shell dumpsys activity services app.gamenative | Select-String -Pattern "NativeRuntimeService|SteamService|isForeground|foregroundId|startForegroundCount" -CaseSensitive:$false
```

Resultado esperado:

- `NativeRuntimeService`
- `isForeground=true`
- `foregroundId=42`
- `SteamService` tambem em foreground quando Steam estiver em uso

## Estado Atual das Melhorias de Runtime

Ja foi implementado:

- `NativeRuntimeService` para manter runtime nativo ativo em foreground.
- Root Boost com cgroup/cpuset.
- `oom_score_adj=-800` nos processos do jogo.
- `/dev/cpuset/gamenative` e `/dev/cpuctl/gamenative`.
- `cpu.uclamp.min=70`.
- `cpu.uclamp.latency_sensitive=1`.
- Monitor que reaplica otimizacoes enquanto houver processos Wine/FEX/Box64.
- Modo sem root continua existindo.
- Com Root Boost ativo, o app evita pausar o container ao minimizar.

## Problema Encontrado no GTA V

Antes, o perfil `extreme` prendia o `GTA5.exe` somente nos CPUs `6-7`.

Isso era ruim para GTA V porque o jogo usa muitas threads durante carregamento e streaming de assets.

Foi corrigido para nao usar somente os prime cores no processo principal.

Estado visto depois da correcao:

```text
GTA5.exe
/gamenative
Cpus_allowed_list: 0-5
Threads: 113
VmRSS: ~2.2 GB
State: R
GPU usage: ~84%
```

Melhorou porque nao esta mais preso em `6-7`, mas ainda falta avaliar se o ideal no Snapdragon e usar `0-7` sempre para GTA V ou usar um perfil automatico por jogo.

## O Que Falta Melhorar no Root Boost

Pendencias:

- Criar perfil por jogo para CPU affinity:
  - GTA V provavelmente deve usar `0-7` ou `0-5` dependendo da estabilidade termica.
  - Jogos leves podem usar apenas big/prime cores.
  - Launchers e processos auxiliares devem ficar fora dos nucleos principais.
- Separar processos por tipo:
  - `GTA5.exe`: CPU list ampla.
  - `wineserver`: CPU list ampla ou big cores.
  - `winedevice.exe`, launchers, helpers: CPU list menor.
  - audio/pulseaudio: prioridade estavel, sem competir com jogo.
- Criar modo thermal-aware:
  - Se CPU/GPU estiver muito quente, reduzir uclamp/affinity.
  - Temperaturas vistas: CPU ~83 C, GPU ~70 C.
- Melhorar diagnostico no app:
  - Mostrar cpuset atual.
  - Mostrar CPU affinity real.
  - Mostrar se Root Boost foi aplicado com sucesso.
  - Mostrar ultima falha de `su`.
- Evitar mudancas agressivas no sistema inteiro.
- Testar `safe`, `performance` e `extreme` em pelo menos 3 jogos.

## Problema de Idioma dos Jogos

Sintoma:

- Container configurado como `Portuguese (Brazil)` ou `Portuguese`, mas o jogo continua em ingles.

Causas encontradas:

- O app usava idioma do container em partes de download/Steam, mas nem todos os launchers aplicavam idioma no ambiente real do Wine.
- Alguns caminhos ainda usavam valores fixos como `en-US` ou `English`.
- GOG tinha script interpreter com `/Language=English` e `/LANG=English`.
- Epic usava `-epiclocale=en-US` por padrao.
- Se o jogo foi baixado antes de trocar idioma, os arquivos/depot de idioma podem nao existir localmente.

## Correcao de Idioma Ja Iniciada

Foi criado:

```text
app/src/main/java/com/winlator/core/RuntimeLocaleHelper.java
```

Objetivo:

- Centralizar todos os idiomas em um unico mapeamento.
- Aplicar idioma em Wine/Linux:
  - `LANG`
  - `LANGUAGE`
  - `LC_ALL`
  - `LC_CTYPE`
  - `LC_MESSAGES`
- Aplicar idioma em Steam:
  - `SteamLanguage`
  - `SteamUILanguage`
  - `SteamClientLanguage`
  - `STEAM_LANGUAGE`
- Aplicar idioma em Epic:
  - `-epiclocale=...`
- Aplicar idioma em GOG script interpreter:
  - `/Language=...`
  - `/LANG=...`
  - `/lang-code=...`

Mapeamentos importantes:

```text
english     -> en_US.utf8 / english / en-US / English
portuguese  -> pt_PT.utf8 / portuguese / pt-PT / Portuguese
brazilian   -> pt_BR.utf8 / brazilian / pt-BR / PortugueseBrazilian
spanish     -> es_ES.utf8 / spanish / es-ES / Spanish
latam       -> es_MX.utf8 / latam / es-MX / SpanishLatinAmerica
french      -> fr_FR.utf8 / french / fr-FR / French
german      -> de_DE.utf8 / german / de-DE / German
italian     -> it_IT.utf8 / italian / it-IT / Italian
japanese    -> ja_JP.utf8 / japanese / ja-JP / Japanese
koreana     -> ko_KR.utf8 / koreana / ko-KR / Korean
schinese    -> zh_CN.utf8 / schinese / zh-CN / ChineseSimplified
tchinese    -> zh_TW.utf8 / tchinese / zh-TW / ChineseTraditional
```

## O Que Ainda Falta para Corrigir Idioma de Forma Completa

Pendencias tecnicas:

- Confirmar no processo real do jogo se as variaveis chegam ao Wine:
  - `LANG`
  - `LC_ALL`
  - `SteamLanguage`
  - `SteamUILanguage`
- Ajustar leitura de `/proc/<pid>/environ`; em alguns casos precisa root e permissao correta.
- Garantir que `configs.user.ini` e `configs.app.ini` do Goldberg/Steam sejam regravados ao trocar idioma do container.
- Garantir que `appmanifest_<appid>.acf` atualize:
  - `UserConfig.language`
  - `MountedConfig.language`
- Quando o usuario trocar idioma depois do jogo ja baixado:
  - detectar se existem depots/arquivos de idioma faltando.
  - oferecer redownload/sync do idioma.
- Corrigir gamefixes com idioma fixo:
  - exemplo atual: `GOG_1141086411.kt` usa `"Install Language" to "English"`.
  - ideal: gamefix receber `container` ou `RuntimeLocaleHelper.installerLanguageForContainer(container)`.
- Testar Steam, GOG e Epic separadamente.

## Test Plan para Proxima IA

1. Compilar `modernDebug`.
2. Gerar APK universal.
3. Instalar no smartphone.
4. Configurar container como `brazilian`.
5. Abrir GTA V ou outro jogo com idioma suportado.
6. Verificar logs do launcher:
   - deve aparecer env com `LANG=pt_BR.utf8`.
   - deve aparecer `SteamLanguage=brazilian`.
7. Verificar arquivos Steam/GSE:
   - `configs.user.ini` com `language=brazilian`.
   - `appmanifest_<appid>.acf` com `language=brazilian`.
8. Se o jogo continuar em ingles:
   - verificar se arquivos de idioma foram baixados.
   - verificar parametros especificos do jogo.
   - verificar configs salvas dentro de `Documents`, `AppData`, `ProgramData` ou pasta do jogo.

## Recomendacao para Proxima Etapa

Prioridade 1:

- Finalizar correcao global de idioma e validar no processo real.

Prioridade 2:

- Criar perfis de CPU por jogo, com GTA V usando CPU list ampla.

Prioridade 3:

- Adicionar tela de diagnostico no app mostrando:
  - idioma efetivo.
  - Steam language.
  - locale Wine.
  - cpuset.
  - CPU affinity.
  - Root Boost status.

Prioridade 4:

- Melhorar auto-tuning com estatisticas reais por jogo.

## Observacoes

- Nao usar build legacy neste smartphone.
- Sempre instalar o APK `universal-modern-debug.apk`.
- Ao reinstalar o app via `adb install -r`, o jogo em execucao pode ser fechado.
- Se GTA V ficar carregando, verificar primeiro:
  - CPU affinity.
  - Steam watchdog/desconexao.
  - Rockstar/SocialClub.
  - arquivos de idioma/cache/config.
  - temperatura e throttling.
