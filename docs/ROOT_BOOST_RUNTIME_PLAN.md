# Root Boost / Native Runtime Plan

## Resumo

Este documento registra um caminho seguro para trazer ideias do DroidSpaces para o GameNative sem converter o emulador inteiro para LXC.

O foco e criar um modo opcional **Root Boost / Native Runtime** para melhorar performance, estabilidade e controle de processos quando o usuario tiver root e kernel compativel. O modo atual do GameNative deve continuar funcionando normalmente sem root.

O objetivo nao e substituir Wine/Proton, FEXCore/Box64, DXVK/VKD3D, Turnip/Adrenotools ou os containers atuais. O objetivo e adicionar uma camada opcional de controle de recursos, diagnostico e lifecycle para execucoes de jogos.

## Base Tecnica Observada

DroidSpaces usa uma arquitetura de container Linux nativo baseada em namespaces, cgroups, `pivot_root`, bind mounts, acesso controlado a GPU/hardware e monitoramento de container. Ele nao depende do projeto LXC classico via comandos `lxc-start`/`lxc-attach`, mas implementa primitivas equivalentes do kernel Linux.

GameNative usa uma arquitetura diferente: Wine/Proton, FEXCore/Box64, DXVK/VKD3D, Turnip/Adrenotools, Mesa/Zink e containers proprios por jogo. O gargalo principal do GameNative normalmente esta em traducao x86/x64, Wine, renderer grafico, driver Vulkan e comportamento termico do Android.

Portanto, o ganho esperado nao vem de "virar LXC". O ganho esperado vem de aplicar ideias de controle de recursos, isolamento operacional, validacao de hardware e recuperacao de runtime.

## Melhorias Propostas

- **Root performance profile**: criar perfis `off`, `safe`, `performance` e `extreme` para controlar governor, prioridade de processo, CPU affinity e comportamento thermal-aware.
- **Cgroups/cpuset opcionais**: reservar nucleos fortes para Wine/FEXCore/Box64 e limitar processos auxiliares como launcher, updater, helper de loja e servicos que nao precisam disputar CPU durante o jogo.
- **GPU/device access validation**: detectar `/dev/kgsl-*`, `/dev/dri/*`, suporte Vulkan, driver Turnip/Adrenotools selecionado e falhas conhecidas antes do launch.
- **Runtime monitor**: rastrear PIDs do jogo, Wine/Proton, FEXCore/Box64, renderer, audio, X/Wayland bridge e launcher.
- **Recovery**: limpar processos travados, registrar logs uteis e manter estado claro de execucao: `starting`, `running`, `stopped`, `crashed`.
- **Per-game runtime profile**: salvar metricas por jogo e usar estatisticas reais para recomendar configuracoes automaticamente.

## Fases de Implementacao

### Fase 1: Documentacao, medicoes e diagnostico

- Adicionar diagnostico passivo sem alterar comportamento.
- Registrar informacoes do dispositivo, GPU, driver, Wine/Proton, FEXCore/Box64, DXVK/VKD3D e configuracao de container.
- Medir FPS medio, frametime quando disponivel, duracao da sessao, status final e sinais de crash/freeze.
- Exibir essas informacoes em logs e preparar dados para estatistica futura.

### Fase 2: Root Boost experimental

- Adicionar toggle opcional para Root Boost.
- Detectar root de forma segura e nao intrusiva.
- Se root nao estiver disponivel, manter o fluxo atual e mostrar motivo claro.
- Implementar primeiro o perfil `safe`, evitando ajustes agressivos.
- Registrar tudo que foi aplicado e tudo que falhou.

### Fase 3: Cgroups/cpuset opcionais

- Criar isolamento opcional de CPU/cgroup apenas quando o kernel permitir.
- Priorizar processos principais do jogo e limitar processos auxiliares.
- Evitar obrigar cgroups em aparelhos sem suporte.
- Fazer fallback automatico para o runtime atual se qualquer etapa falhar.

### Fase 4: Monitor e recovery de runtime

- Implementar monitor de lifecycle por sessao de jogo.
- Rastrear PIDs relevantes e estado real da execucao.
- Encerrar processos presos de forma controlada quando o jogo fechar.
- Produzir logs organizados para debug e para submissao futura de estatisticas.

### Fase 5: Auto tuning baseado em estatisticas reais

- Usar metricas coletadas para comparar configuracoes.
- Recomendar perfil root, CPU mode, driver GPU e wrapper grafico por jogo/dispositivo.
- Integrar com o sistema de best-config e estatisticas do GameNative.
- Nunca aplicar configuracao agressiva automaticamente sem evidencia suficiente.

## Criterios de Aceite

- O app continua funcionando sem root.
- Com root, o usuario pode ativar e desativar Root Boost.
- Falha de root, cgroup, governor ou GPU validation nao impede o jogo de abrir no modo atual.
- O app registra FPS medio, frametime quando disponivel, CPU, memoria, GPU driver, config aplicada e status final.
- Nenhum jogo depende obrigatoriamente do novo runtime.
- Se Root Boost falhar, o app cai para o modo atual com erro claro.
- Melhorias sao medidas antes/depois por jogo.
- O modo experimental fica claramente separado do fluxo estavel.

## Interfaces e Configuracoes Planejadas

Adicionar futuramente, nao neste primeiro documento:

### Config por container

- `rootPerformanceProfile`: `off | safe | performance | extreme`
- `runtimeMonitorEnabled`: boolean
- `cpuAffinityMode`: `auto | big_cores | all_cores | custom`
- `gpuValidationMode`: `off | warn | strict`

### Metricas por execucao

- jogo, store, device, GPU e driver
- Wine/Proton, FEXCore/Box64, DXVK/VKD3D e Turnip/Adrenotools
- FPS medio, frametime quando disponivel e duracao da sessao
- status de saida, crash/freeze e tags de falha
- perfil root usado
- config aplicada

## Test Plan

### Documento inicial

- Verificar que este arquivo Markdown abre corretamente.
- Verificar que o plano nao promete LXC obrigatorio.
- Verificar que o plano separa modo sem root e modo root.
- Verificar que as fases sao implementaveis sem quebrar compatibilidade.

### Implementacao futura

- Testar jogo sem root e confirmar comportamento igual ao atual.
- Testar root disponivel com Root Boost desligado.
- Testar Root Boost ligado em modo `safe`.
- Testar falha de permissao root e fallback.
- Testar kernel sem cgroup/cpuset adequado e fallback.
- Comparar FPS/frametime antes e depois em pelo menos 3 jogos:
  - um CPU-bound;
  - um GPU-bound;
  - um launcher pesado como Steam, Epic ou GOG.

## Assumptions

- Este plano e apenas documentacao tecnica inicial.
- Nenhuma mudanca de codigo deve ser feita antes de alinhar este documento.
- A implementacao futura sera opcional e compativel com aparelhos sem root.
- DroidSpaces sera usado como referencia arquitetural, nao como dependencia direta.
- LXC classico nao sera adicionado como requisito.
