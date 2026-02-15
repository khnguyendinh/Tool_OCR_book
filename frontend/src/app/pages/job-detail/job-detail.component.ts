import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { OcrService, OcrJob } from '../../services/ocr.service';

@Component({
    selector: 'app-job-detail',
    standalone: true,
    imports: [CommonModule, RouterLink],
    templateUrl: './job-detail.component.html',
    styleUrl: './job-detail.component.css'
})
export class JobDetailComponent implements OnInit, OnDestroy {
    job: OcrJob | null = null;
    isLoading = true;
    error = '';
    isDownloading = false;
    private jobId = '';
    private refreshInterval: any;

    constructor(
        private route: ActivatedRoute,
        private ocrService: OcrService
    ) { }

    ngOnInit() {
        this.jobId = this.route.snapshot.paramMap.get('id') || '';
        this.loadJob();
        this.refreshInterval = setInterval(() => {
            if (this.job && this.job.status !== 'COMPLETED' && this.job.status !== 'FAILED') {
                this.loadJob();
            }
        }, 2000);
    }

    ngOnDestroy() {
        if (this.refreshInterval) {
            clearInterval(this.refreshInterval);
        }
    }

    loadJob() {
        this.ocrService.getJob(this.jobId).subscribe({
            next: (job) => {
                this.job = job;
                this.isLoading = false;
            },
            error: (err) => {
                this.error = 'Không thể tải thông tin job!';
                this.isLoading = false;
                console.error(err);
            }
        });
    }

    downloadResult() {
        if (!this.job) return;
        this.isDownloading = true;

        this.ocrService.downloadResult(this.jobId).subscribe({
            next: (blob) => {
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = this.job!.originalFileName.replace(/\.[^.]+$/, '') + '_ocr_result.docx';
                document.body.appendChild(a);
                a.click();
                window.URL.revokeObjectURL(url);
                document.body.removeChild(a);
                this.isDownloading = false;
            },
            error: (err) => {
                this.isDownloading = false;
                console.error('Download error:', err);
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
            'FAILED': '❌ Lỗi',
            'PENDING': '⏳ Chờ',
        };
        return labels[status] || status;
    }

    formatDate(date: string): string {
        if (!date) return '—';
        return new Date(date).toLocaleString('vi-VN');
    }

    getTaskStatusIcon(status: string): string {
        const icons: Record<string, string> = {
            'PENDING': '⏳',
            'PROCESSING': '🔄',
            'COMPLETED': '✅',
            'FAILED': '❌'
        };
        return icons[status] || '❓';
    }
}
