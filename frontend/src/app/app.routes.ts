import { Routes } from '@angular/router';
import { UploadComponent } from './pages/upload/upload.component';
import { JobListComponent } from './pages/job-list/job-list.component';
import { JobDetailComponent } from './pages/job-detail/job-detail.component';

export const routes: Routes = [
    { path: '', redirectTo: '/upload', pathMatch: 'full' },
    { path: 'upload', component: UploadComponent },
    { path: 'jobs', component: JobListComponent },
    { path: 'jobs/:id', component: JobDetailComponent },
];
