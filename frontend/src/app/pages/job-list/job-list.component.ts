import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { OcrService, OcrJob } from '../../services/ocr.service';

@Component({
    selector: 'app-job-list',
    standalone: true,
    imports: [CommonModule, RouterLink],
    templateUrl: './job-list.component.html',
    styleUrl: './job-list.component.css'
})
export class JobListComponent implements OnInit, OnDestroy {
    jobs: OcrJob[] = [];
    isLoading = true;
    error = '';
    private refreshInterval: any;

    constructor(private ocrService: OcrService) { }

    ngOnInit() {
        this.loadJobs();
        this.refreshInterval = setInterval(() => this.loadJobs(), 3000);
    }

    ngOnDestroy() {
        if (this.refreshInterval) {
            clearInterval(this.refreshInterval);
        }
    }

    loadJobs() {
        this.ocrService.getJobs().subscribe({
            next: (jobs) => {
                this.jobs = jobs;
                this.isLoading = false;
            },
            error: (err) => {
                this.error = 'Không thể kết nối server!';
                this.isLoading = false;
                console.error(err);
            }
        });
    }

    getStatusClass(status: string): string {
        return 'badge-' + status.toLowerCase();
    }

    getStatusLabel(status: string): string {
        const labels: Record<string, string> = {
            'SPLITTING': '⚡ Đang chia',
            'QUEUED': '📨 Đã đẩy queue',
            'PROCESSING': '🔄 Đang xử lý OCR',
            'COMPLETED': '✅ Hoàn thành',
            'FAILED': '❌ Lỗi'
        };
        return labels[status] || status;
    }

    formatDate(date: string): string {
        if (!date) return '—';
        return new Date(date).toLocaleString('vi-VN');
    }
}
