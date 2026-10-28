// Prueba de carga contra Cloud Firestore REAL del proyecto, con un usuario anonimo creado solo para la prueba.
// Escribe gastos en tandas crecientes, mide latencia por tanda, lee con y sin acotar y borra TODO al final
// (documentos y cuenta de prueba). Consume del orden de 500 escrituras, 500 borrados y 4.000 lecturas de las cuotas diarias.
//
// Uso:  node pruebas/carga-nube-real.mjs <API_KEY_WEB_DEL_PROYECTO>
const CLAVE = process.argv[2];
if (!CLAVE) { console.error('Falta la clave de API'); process.exit(1); }
const PROYECTO = 'finanzas-tp2-utn';
const RAIZ = `https://firestore.googleapis.com/v1/projects/${PROYECTO}/databases/(default)/documents`;
const TANDAS = [50, 100, 150, 200]; // 500 escrituras en total, de a tandas simultaneas crecientes

const percentil = (v, p) => { const s = [...v].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
const gasto = (i) => ({
  fields: {
    monto: { doubleValue: 100 + i },
    categoria: { stringValue: 'prueba-carga' },
    detalle: { stringValue: 'Prueba de carga' },
    fecha: { timestampValue: new Date(Date.now() - (i % 60) * 86400000).toISOString() },
    medioId: { stringValue: 'efectivo' },
    fijoId: { stringValue: '' },
  },
});

// 1. Usuario anonimo de prueba
const reg = await (await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${CLAVE}`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ returnSecureToken: true }),
})).json();
if (!reg.idToken) { console.error('No se pudo crear el usuario de prueba', JSON.stringify(reg).slice(0, 200)); process.exit(1); }
const UID = reg.localId;
const AUTH = { Authorization: `Bearer ${reg.idToken}`, 'Content-Type': 'application/json' };
console.log('Usuario anonimo de prueba creado:', UID.slice(0, 6) + '...');

const ids = [];
let errores = 0;
try {
  // 2. Escrituras en tandas crecientes
  console.log('\n--- Escrituras en tandas simultaneas crecientes ---');
  let n = 0;
  const t0 = performance.now();
  for (const tam of TANDAS) {
    const ini = performance.now();
    const lat = await Promise.all(Array.from({ length: tam }, async () => {
      const i = n++;
      const t = performance.now();
      const r = await fetch(`${RAIZ}/usuarios/${UID}/gastos`, { method: 'POST', headers: AUTH, body: JSON.stringify(gasto(i)) });
      const ms = performance.now() - t;
      if (r.ok) { ids.push((await r.json()).name); } else { errores++; }
      return ms;
    }));
    const seg = (performance.now() - ini) / 1000;
    console.log(`tanda de ${String(tam).padStart(3)} escrituras simultaneas: ${(tam / seg).toFixed(0).padStart(4)} ops/s | mediana ${percentil(lat, 0.5).toFixed(0)} ms | p95 ${percentil(lat, 0.95).toFixed(0)} ms | max ${Math.max(...lat).toFixed(0)} ms`);
    await new Promise((r) => setTimeout(r, 1500)); // respiro entre tandas
  }
  console.log(`escrituras aceptadas: ${ids.length}/${n} | errores: ${errores} | tiempo total ${((performance.now() - t0) / 1000).toFixed(1)} s`);

  // 3. Lecturas: acotada por rango/limite frente a la coleccion completa
  console.log('\n--- Lecturas ---');
  const consulta = async (q) => {
    const t = performance.now();
    const r = await fetch(`${RAIZ}/usuarios/${UID}:runQuery`, { method: 'POST', headers: AUTH, body: JSON.stringify({ structuredQuery: q }) });
    const filas = (await r.json()).filter((x) => x.document);
    return { docs: filas.length, ms: performance.now() - t };
  };
  const completa = await consulta({ from: [{ collectionId: 'gastos' }] });
  const pagina = await consulta({ from: [{ collectionId: 'gastos' }], orderBy: [{ field: { fieldPath: 'fecha' }, direction: 'DESCENDING' }], limit: 30 });
  console.log(`coleccion completa : ${completa.docs} documentos en ${completa.ms.toFixed(0)} ms`);
  console.log(`pagina de treinta  : ${pagina.docs} documentos en ${pagina.ms.toFixed(0)} ms`);
} finally {
  // 4. Limpieza total
  console.log('\n--- Limpieza ---');
  let borrados = 0;
  const lote = 50;
  for (let i = 0; i < ids.length; i += lote) {
    const rs = await Promise.all(ids.slice(i, i + lote).map((nombre) =>
      fetch(`https://firestore.googleapis.com/v1/${nombre}`, { method: 'DELETE', headers: AUTH })));
    borrados += rs.filter((r) => r.ok).length;
  }
  const baja = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:delete?key=${CLAVE}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ idToken: reg.idToken }),
  });
  console.log(`documentos borrados: ${borrados}/${ids.length} | cuenta de prueba eliminada: ${baja.ok}`);
}
