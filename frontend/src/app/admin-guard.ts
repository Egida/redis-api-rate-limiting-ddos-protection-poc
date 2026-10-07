import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AdminApiService } from './core/admin-api.service';

/**
 * Gates every console route behind admin authentication. Unauthenticated users are redirected to
 * login with the original URL preserved in the query string for post-login navigation.
 */
export const adminGuard: CanActivateFn = (route, state) => {
  if (inject(AdminApiService).loggedIn()) return true;
  return inject(Router).createUrlTree(['/admin/login'], { queryParams: { returnUrl: state.url } });
};
