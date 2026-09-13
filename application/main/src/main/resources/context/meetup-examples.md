Каждый пример — «сообщение из группы → ожидаемый итоговый JSON». Примеры показывают
типовые случаи; действуй по правилам выше, а не механически копируй примеры.

## Пример 1 — полное сообщение, все данные в тексте

Сообщение:

> 25 сентября в 19:00 в «Севкабель Порт» (Санкт-Петербург, Кожевенная линия, 40) —
> бесплатный митап «Kotlin в продакшене» от сообщества SPb Kotlin. Вход свободный,
> нужна регистрация: https://timepad.ru/x/kotlin-prod
> Доклады: «Корутины на практике» — Иван Петров; «Compose Desktop» — Мария Соколова.

Итоговый JSON:

```json
{"title":"Kotlin в продакшене","description":"Бесплатный митап сообщества SPb Kotlin: корутины и Compose Desktop","organizer":"SPb Kotlin","city":"Санкт-Петербург","isFree":true,"price":null,"formats":["OFFLINE"],"address":"Кожевенная линия, 40","venueName":"Севкабель Порт","startsAt":"2026-09-25T19:00+03:00","endsAt":null,"talks":[{"title":"Корутины на практике","speaker":"Иван Петров","description":null},{"title":"Compose Desktop","speaker":"Мария Соколова","description":null}],"registrationUrl":"https://timepad.ru/x/kotlin-prod","sourceUrls":["https://timepad.ru/x/kotlin-prod"],"language":"RU","verdict":{"status":"APPROVED","reasons":[]},"confidence":0.95}
```

## Пример 2 — сообщение только со ссылкой

Сообщение:

> https://habr.com/companies/piterjs/events/ — гляньте, тут всё по программе

В сообщении данных нет — вызови `fetch_web_page` со ссылкой. Страница (сообщество
PiterJS): митап 2 октября, 18:30, «Севкабель Порт», вход бесплатный по регистрации.
Извлеки данные со страницы, а не из сообщения:

```json
{"title":"PiterJS #60","description":"Осенний митап PiterJS","organizer":"PiterJS","city":"Санкт-Петербург","isFree":true,"price":null,"formats":["OFFLINE"],"address":"Кожевенная линия, 40","venueName":"Севкабель Порт","startsAt":"2026-10-02T18:30+03:00","endsAt":null,"talks":[],"registrationUrl":"https://timepad.ru/x/piterjs60","sourceUrls":["https://habr.com/companies/piterjs/events/","https://timepad.ru/x/piterjs60"],"language":"RU","verdict":{"status":"APPROVED","reasons":[]},"confidence":0.82}
```

Если страница не открылась (таймаут, 4xx/5xx) — верни вердикт NEEDS_REVIEW с причиной
MISSING_DATA и низким confidence, не выдумывая деталей.

## Пример 3 — платное мероприятие

Сообщение:

> Конференция «Frontend Day», 14 ноября, Москва + онлайн. Билеты: от 3500 ₽,
> регистрация на сайте frontendday.ru

Платное — не проходит отбор независимо от города; isFree=false, price — минимальная
указанная цена:

```json
{"title":"Frontend Day","description":"Конференция по фронтенду","organizer":null,"city":"Москва","isFree":false,"price":"от 3500 ₽","formats":["OFFLINE","ONLINE"],"address":null,"venueName":null,"startsAt":"2026-11-14T00:00+03:00","endsAt":null,"talks":[],"registrationUrl":"https://frontendday.ru","sourceUrls":["https://frontendday.ru"],"language":"RU","verdict":{"status":"REJECTED","reasons":["PAID","NOT_SPB"]},"confidence":0.9}
```

## Пример 4 — город не назван, но следует из адреса

Сообщение:

> Митап «SRE Практикум» в следующую среду, 19:00, Площадь Конституции, 2 (вход со
> двора, 2 этаж). Участие бесплатное, программа и регистрация: https://timepad.ru/x/sre

Город в сообщении не упомянут, но «Площадь Конституции, 2» — это Санкт-Петербург
(площадь у станции метро «Технологический институт» в СПб). Адрес относится
к СПб → город «Санкт-Петербург», вердикт APPROVED. Аналогично выводятся адреса
типа «Кронверкский пр., 49» (СПб). Если же по тексту город не определяется
однозначно (например, «Ленинский проспект» есть и в Москве, и в СПб) —
NEEDS_REVIEW (MISSING_DATA):

```json
{"title":"SRE Практикум","description":"Бесплатный митап о SRE-практиках","organizer":null,"city":"Санкт-Петербург","isFree":true,"price":null,"formats":["OFFLINE"],"address":"Площадь Конституции, 2","venueName":null,"startsAt":"2026-09-23T19:00+03:00","endsAt":null,"talks":[],"registrationUrl":"https://timepad.ru/x/sre","sourceUrls":["https://timepad.ru/x/sre"],"language":"RU","verdict":{"status":"APPROVED","reasons":[]},"confidence":0.78}
```
