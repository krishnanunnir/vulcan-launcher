# Translating Vulcan

Every word Vulcan shows is in [`app/src/main/res/values/strings.xml`](../app/src/main/res/values/strings.xml) (English). To add a language, copy it to `values-<code>/strings.xml` (for example `values-de` for German) and translate the text between the tags. Leave the `name` attributes and placeholders such as `%1$s` and `%1$d` as they are; a string you leave out shows in English. Anything marked `translatable="false"` stays as it is. The unit tests check that every translated name and placeholder matches the English file.

The settings' stored values, your layout and backup files don't depend on the language, so switching languages changes nothing else. The explanation inside a backup file (`howTo`) stays in English; it's written for whoever edits the file.
