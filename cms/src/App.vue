<script setup lang="ts">
// Shell for the KeyQuest admin CMS. Navigation and auth-aware layout land
// with the P1 scaffolding (plan §14).
</script>

<template>
  <div class="cms-shell">
    <header class="cms-shell__header">
      <h1 class="cms-shell__title">KeyQuest <span>CMS</span></h1>
    </header>
    <main class="cms-shell__content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
/* Committed styling: ebony/ivory studio palette with a warm amber accent —
   distinctive over generic (frontend philosophy). */
.cms-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #14100c 0%, #1d1711 100%);
  color: #f2e9d8;
  font-family: 'Avenir Next', 'Segoe UI', sans-serif;
}

.cms-shell__header {
  padding: 1.5rem 2rem;
  border-bottom: 1px solid rgba(242, 233, 216, 0.12);
}

.cms-shell__title {
  margin: 0;
  font-size: 1.4rem;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.cms-shell__title span {
  color: #d9a441;
}

.cms-shell__content {
  flex: 1;
  padding: 2rem;
}
</style>