'use strict';

const formular = document.querySelector('#login-formular');
const kontoname = document.querySelector('#kontoname');
const passwort = document.querySelector('#passwort');
const anmelden = document.querySelector('#anmelden');
const felder = [kontoname, passwort];

function pruefen(feld) {
  let meldung = '';
  if (feld.validity.valueMissing) {
    meldung = feld === kontoname ? 'Bitte geben Sie Ihre E-Mail-Adresse oder Ihren Kontonamen ein.' : 'Bitte geben Sie Ihr Passwort ein.';
  } else if (!feld.validity.valid) {
    meldung = 'Bitte prüfen Sie Ihre Eingabe.';
  }
  document.querySelector(`#${feld.id}-fehler`).textContent = meldung;
  if (meldung) feld.setAttribute('aria-invalid', 'true');
  else feld.removeAttribute('aria-invalid');
  return !meldung;
}

formular.addEventListener('submit', event => {
  const gueltig = felder.map(pruefen).every(Boolean);
  if (!gueltig) {
    event.preventDefault();
    felder.find(feld => !feld.validity.valid)?.focus();
    return;
  }
  anmelden.disabled = true;
  anmelden.textContent = 'Anmelden …';
});
for (const feld of felder) {
  feld.addEventListener('input', () => {
    if (feld.hasAttribute('aria-invalid')) pruefen(feld);
  });
}
window.addEventListener('pageshow', () => {
  anmelden.disabled = false;
  anmelden.textContent = 'Anmelden';
});
// Ohne dieses Skript bleiben die native Formularvalidierung und Anmeldung nutzbar.
formular.noValidate = true;
