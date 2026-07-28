# Публикация LimeFlow на GitHub

## 1. Создание репозитория

Заполните форму **Create a new repository**:

- **Owner:** `Ramixy`
- **Repository name:** `LimeFlow`
- **Description:** `LimeFlow — Android-приложение без root для локальной обработки трафика, проверки DPI-стратегий и маршрутизации выбранных приложений.`
- **Visibility:** `Public`
- **Add README:** `Off`
- **Add .gitignore:** `No .gitignore`
- **Add license:** `No license`

README, `.gitignore` и GPLv3-лицензия уже входят в подготовленный архив. Если
GitHub создаст собственные версии этих файлов, при загрузке возникнут конфликты.

## 2. Загрузка исходников

Распакуйте `LimeFlow-GitHub-Source-1.0.0.zip`, откройте терминал в распакованной
папке и выполните:

```powershell
git init
git add .
git commit -m "Initial release LimeFlow 1.0.0"
git branch -M main
git remote add origin https://github.com/Ramixy/LimeFlow.git
git push -u origin main
```

Если Git попросит авторизацию, войдите через браузер или используйте GitHub
Desktop. Пароль аккаунта в командную строку вводить не нужно.

## 3. Создание релиза

1. Откройте страницу репозитория.
2. Выберите **Releases → Create a new release**.
3. Создайте тег `v1.0.0`.
4. Заголовок: `LimeFlow 1.0.0`.
5. Вставьте текст из `RELEASE_NOTES_1.0.0.md`.
6. Прикрепите `LimeFlow-1.0.0.apk`.
7. Нажмите **Publish release**.

## 4. Рекомендуемые Topics

Добавьте в разделе About:

```text
android vpn dpi privacy kotlin rootless samsung
```

## Важно

Не загружайте keystore, пароли подписи, `local.properties`, папки `.idea`,
`.gradle`, `build` или `.cxx`. Подготовленный архив уже очищен от них.
