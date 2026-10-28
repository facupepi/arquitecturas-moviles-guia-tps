// Comprueba contra Cloud Firestore REAL que la consulta por categoria y fecha, la que resuelve el indice compuesto,
// se ejecuta sin error. Crea un usuario anonimo de prueba, escribe 60 gastos en dos categorias, consulta una categoria
// ordenada por fecha con limite de 30 y borra todo (documentos y cuenta). Consume 60 escrituras, 60 eliminaciones y 30 lecturas.
//
// Uso:  node pruebas/consulta-indice-nube.mjs <API_KEY_WEB_DEL_PROYECTO>
const CLAVE = process.argv[2];
if (!CLAVE) { console.error('Falta la clave de API'); process.exit(1); }
const PROYECTO = 'finanzas-tp2-utn';
const RAIZ = `https://firestore.googleapis.com/v1/projects/${PROYECTO}/databases/(default)/documents`;

const reg = await (await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${CLAVE}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ returnSecureToken: true }),
})).json();
if (!reg.idToken) { console.error('No se pudo crear el usuario de prueba'); process.exit(1); }
const UID = reg.localId;
const AUTH = { Authorization: `Bearer ${reg.idToken}`, 'Content-Type': 'application/json' };
const nombres = [];
try {
  for (let i = 0; i < 60; i++) {
    const r = await fetch(`${RAIZ}/usuarios/${UID}/gastos`, {
      method: 'POST', headers: AUTH, body: JSON.stringify({
        fields: {
          monto: { doubleValue: 100 + i }, categoria: { stringValue: i % 2 ? 'comida' : 'transporte' },
          detalle: { stringValue: 'Prueba de indice' },
          fecha: { timestampValue: new Date(Date.now() - i * 86400000).toISOString() },
          medioId: { stringValue: 'efectivo' }, fijoId: { stringValue: '' },
        },
      }),
    });
    if (r.ok) nombres.push((await r.json()).name);
  }
  console.log(`gastos escritos: ${nombres.length}/60`);
  const t = performance.now();
  const r = await fetch(`${RAIZ}/usuarios/${UID}:runQuery`, {
    method: 'POST', headers: AUTH, body: JSON.stringify({
      structuredQuery: {
        from: [{ collectionId: 'gastos' }],
        where: { fieldFilter: { field: { fieldPath: 'categoria' }, op: 'EQUAL', value: { stringValue: 'comida' } } },
        orderBy: [{ field: { fieldPath: 'fecha' }, direction: 'DESCENDING' }],
        limit: 30,
      },
    }),
  });
  const cuerpo = await r.json();
  const docs = Array.isArray(cuerpo) ? cuerpo.filter((x) => x.document).length : 0;
  console.log(`consulta categoria + fecha (indice compuesto): estado ${r.status} | ${docs} documentos | ${(performance.now() - t).toFixed(0)} ms`);
  if (!r.ok) console.log('detalle:', JSON.stringify(cuerpo).slice(0, 200));
} finally {
  let borrados = 0;
  for (let i = 0; i < nombres.length; i += 30) {
    const rs = await Promise.all(nombres.slice(i, i + 30).map((n) => fetch(`https://firestore.googleapis.com/v1/${n}`, { method: 'DELETE', headers: AUTH })));
    borrados += rs.filter((x) => x.ok).length;
  }
  const baja = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:delete?key=${CLAVE}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ idToken: reg.idToken }),
  });
  console.log(`documentos eliminados: ${borrados}/${nombres.length} | cuenta de prueba eliminada: ${baja.ok}`);
}
