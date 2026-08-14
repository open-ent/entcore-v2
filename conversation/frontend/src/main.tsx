import React, { StrictMode } from 'react';

import { EdificeThemeProvider } from '@open-ent/react';
import { createRoot } from 'react-dom/client';

import { RouterProvider } from 'react-router-dom';
import './i18n';
import { Providers, queryClient } from './providers';
import { router } from './routes';

// Le bootstrap openent n'est plus bundlé : il est chargé au runtime via
// <link href="/assets/themes/openent-bootstrap/index.css"> dans index.html.
// Sans ça, chaque changement de charte (ex. le magenta eclat-bfc) imposait de
// recompiler et redéployer le module.
import './index.css';

const rootElement = document.getElementById('root');
const root = createRoot(rootElement!);

if (process.env.NODE_ENV !== 'production') {
  import('@axe-core/react').then((axe) => {
    axe.default(React, root, 1000);
  });
}

root.render(
  <StrictMode>
    <Providers>
      <EdificeThemeProvider>
        <RouterProvider router={router(queryClient)} />
      </EdificeThemeProvider>
    </Providers>
  </StrictMode>,
);
