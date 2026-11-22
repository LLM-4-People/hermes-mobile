# Turn a code review into a form on Android

A local Qwen2.5-Coder-7B-Instruct model answered two French requests through the Hermes gateway. The Android app reopened those same two replies from the conversation. The screenshots show the form it rendered, the changes it found and the choices it offered.

## Ask for a form

> Crée une page personnelle intitulée « Revue des remises » que je pourrai épingler à cette discussion. Je veux y remplir un seul formulaire avec deux champs : le nom du fichier et le point de la revue de code à vérifier. Quand je le remplis, je veux pouvoir relire ma demande avant de l’envoyer.

The first reply created **Revue des remises** (`page/revue-remises`) with **Nom du fichier** (`nom-fichier`) and **Point de revue** (`point-revise`). **Préparer un brouillon…** prepares an editable message. **Épingler à cette discussion** keeps the form in this thread.

## Add a priority choice

> Garde les deux champs de ma fiche. Ajoute un choix de priorité pour distinguer les points à traiter aujourd’hui de ceux qui peuvent attendre.

After the first form was pinned, the discussion offered **Ouvrir l’outil épinglé** on the original reply and **Comparer les versions** on the revision.

<a href="examples/revue-des-remises/30-formulaire-modele-local.png"><img src="examples/revue-des-remises/30-formulaire-modele-local.png" width="300" alt="Trial form with empty filename and review-point fields, a draft action and a button to pin the form."></a>
<a href="examples/revue-des-remises/31-discussion-modele-local.png"><img src="examples/revue-des-remises/31-discussion-modele-local.png" width="300" alt="The conversation shows both French requests, the pinned first form and the action to compare the revision."></a>

## Compare the two versions

The revision kept the same page and both field IDs. **Écarts** shows one added required field, **Priorité** (`priorite`). In **Proposée**, its menu offers **Aujourd'hui** and **À traiter plus tard**. The person can inspect these choices before selecting **Adopter cette version** or **Garder la version épinglée**.

<a href="examples/revue-des-remises/32-revision-modele-local.png"><img src="examples/revue-des-remises/32-revision-modele-local.png" width="300" alt="The changes tab lists the added priority field, its two options and the actions to adopt or keep a version."></a>
<a href="examples/revue-des-remises/33-priorite-modele-local.png"><img src="examples/revue-des-remises/33-priorite-modele-local.png" width="300" alt="The proposed form retains both original inputs and opens the priority menu with Aujourd'hui and À traiter plus tard."></a>

The phone displayed the original assistant messages `2` and `5` from this example conversation. Both reply texts matched the completed gateway runs byte for byte. The receipt contains the exact replies, model and build identifiers, and SHA-256 hashes for the four original captures. [Redacted capture receipt](examples/revue-des-remises/receipt.json)

<!-- Generated from docs/sources/docs/local-model-review.md.in by scripts/docs.py. -->
