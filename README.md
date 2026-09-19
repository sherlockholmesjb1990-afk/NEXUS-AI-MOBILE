# GITHUB_FIX_V1_44_1

Substituir no GitHub o arquivo:

.github/workflows/main.yml

O projeto Android está dentro de:

NEXUS_V1_44_CORE_X/

Esta correção:
- remove a chave YAML quebrada `if-no-files- / found`;
- evita a instalação do pacote legado `tools` no setup-android;
- usa Java 17 com setup-java v5;
- executa os comandos Gradle dentro de `NEXUS_V1_44_CORE_X`;
- instala Android SDK 35/build-tools 35.0.0;
- mantém test + assembleDebug + upload do APK.

Depois do commit, aguardar o novo GitHub Actions.
Não alterar Kotlin/Java ainda: o próximo erro real do build deve ser tratado somente depois desta execução.
