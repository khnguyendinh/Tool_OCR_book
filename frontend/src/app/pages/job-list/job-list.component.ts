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
    isClearing = false;
    showClearDialog = false;
    pendingToConfirm: OcrJob[] = [];
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
                this.error = '';  // server kết nối lại được (vd: sau khi restart) → bỏ thông báo lỗi cũ
                this.isLoading = false;
            },
            error: (err) => {
                this.error = 'Không thể kết nối server!';
                this.isLoading = false;
                console.error(err);
            }
        });
    }

    /** Job chưa xong: đang chia, đã đẩy queue, đang OCR (có thể đang treo). */
    get activeJobs(): OcrJob[] {
        return this.jobs.filter(j => j.status !== 'COMPLETED' && j.status !== 'FAILED');
    }

    onClearClick() {
        // Không có job đang chờ → xoá luôn các job đã xong, không cần hỏi
        if (this.activeJobs.length === 0) {
            this.clearHistory(false);
        } else {
            this.pendingToConfirm = this.activeJobs;
            this.showClearDialog = true;
        }
    }

    clearHistory(includeActive: boolean) {
        this.showClearDialog = false;
        this.isClearing = true;
        this.ocrService.clearHistory(includeActive).subscribe({
            next: () => {
                this.isClearing = false;
                this.loadJobs();
            },
            error: (err) => {
                this.isClearing = false;
                alert('Không xoá được lịch sử!');
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

    /** Thời gian đã chạy kể từ lúc tạo job, ví dụ "1 giờ 25 phút". */
    formatElapsed(date: string): string {
        if (!date) return '—';
        const minutes = Math.max(0, Math.floor((Date.now() - new Date(date).getTime()) / 60000));
        if (minutes < 1) return 'dưới 1 phút';
        const days = Math.floor(minutes / 1440);
        const hours = Math.floor((minutes % 1440) / 60);
        const mins = minutes % 60;
        return [days ? `${days} ngày` : '', hours ? `${hours} giờ` : '', mins ? `${mins} phút` : '']
            .filter(Boolean).join(' ');
    }

    formatDate(date: string): string {
        if (!date) return '—';
        return new Date(date).toLocaleString('vi-VN');
    }
}
