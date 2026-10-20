const assert = require('node:assert');
const { decode, cityCode } = require("./city.js");
// City codes the app showed on the phone for these links.
assert.strictEqual(cityCode('https://github.com/skylightlim/skyline-qr', 'site'), 'PZAT6C');
assert.strictEqual(cityCode('https://um.edu.my/', 'site'), 'Y75WW1');
// The link the app opened in the deep-link test: um.edu.my, dusk, storm.
const city = decode('?c=AQEvaHR0cHM6Ly91bS5lZHUubXkv');
assert.deepStrictEqual(city, { url: 'https://um.edu.my/', generatorVersion: 1, scope: 'site', time: 'Dusk', weather: 'Storm' });
assert.strictEqual(decode('?c=!!!'), null);
assert.strictEqual(decode('?c=AgEAaHR0cHM6Ly9hLmNvLw'), null); // a newer link format
assert.strictEqual(decode('?c=' + Buffer.from([1, 1, 0, ...Buffer.from('javascript:alert(1)')]).toString('base64url')), null);
console.log('city.js agrees with the app');
