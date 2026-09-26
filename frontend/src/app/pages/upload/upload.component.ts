import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpEventType } from '@angular/common/http';
import { OcrMode, OcrService } from '../../services/ocr.service';

@Component({
    selector: 'app-upload',
    standalone: true,
    imports: [CommonModule, FormsModule],
    templateUrl: './upload.component.html',
    styleUrl: './upload.component.css'
})
export class UploadComponent implements OnInit {
    selectedFiles: File[] = [];
    ocrMode: OcrMode = 'GEMINI';
    currentIndex = 0;
    failedFiles: string[] = [];
    createdJobIds: string[] = [];
    numParts = 100;
    splitDir = '';
    outputDir = '';
    isDragOver = false;
    isUploading = false;
    uploadProgress = 0;
    errorMessage = '';

    constructor(
        private ocrService: OcrService,
        private router: Router
    ) { }

    ngOnInit() {
        this.ocrService.getConfig().subscribe({
            next: (config) => {
                this.numParts = config.splitParts;
                this.splitDir = config.splitDir || '';
                this.outputDir = config.outputDir || '';
            },
            error: () => { } // silently ignore if server not running
        });
    }

    onDragOver(event: DragEvent) {
        event.preventDefault();
        event.stopPropagation();
        this.isDragOver = true;
    }

    onDragLeave(event: DragEvent) {
        event.preventDefault();
        event.stopPropagation();
        this.isDragOver = false;
    }

    onDrop(event: DragEvent) {
        event.preventDefault();
        event.stopPropagation();
        this.isDragOver = false;

        const files = event.dataTransfer?.files;
        if (files && files.length > 0) {
            this.addFiles(Array.from(files));
        }
    }

    onFileSelected(event: Event) {
        const input = event.target as HTMLInputElement;
        if (input.files && input.files.length > 0) {
            this.addFiles(Array.from(input.files));
        }
        // Reset để chọn lại cùng file vẫn kích hoạt sự kiện change
        input.value = '';
    }

    addFiles(files: File[]) {
        const pdfs = files.filter(f => f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf'));
        const skipped = files.length - pdfs.length;

        // Bỏ qua file trùng (cùng tên + dung lượng)
        for (const file of pdfs) {
            const exists = this.selectedFiles.some(f => f.name === file.name && f.size === file.size);
            if (!exists) {
                this.selectedFiles.push(file);
            }
        }

        this.errorMessage = skipped > 0 ? `Đã bỏ qua ${skipped} file không phải PDF!` : '';
    }

    removeFile(index: number) {
        this.selectedFiles.splice(index, 1);
        this.errorMessage = '';
    }

    clearFiles() {
        this.selectedFiles = [];
        this.errorMessage = '';
    }

    get totalSize(): number {
        return this.selectedFiles.reduce((sum, f) => sum + f.size, 0);
    }

    formatFileSize(bytes: number): string {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    upload() {
        if (this.selectedFiles.length === 0) return;
        if (this.numParts < 1 || this.numParts > 10000) {
            this.errorMessage = 'Số phần phải từ 1 đến 10000!';
            return;
        }

        this.isUploading = true;
        this.errorMessage = '';
        this.currentIndex = 0;
        this.failedFiles = [];
        this.createdJobIds = [];
        this.uploadNext();
    }

    /** Upload tuần tự từng file, mỗi file tạo một job riêng. */
    private uploadNext() {
        if (this.currentIndex >= this.selectedFiles.length) {
            this.finishUpload();
            return;
        }

        const file = this.selectedFiles[this.currentIndex];
        this.uploadProgress = 0;

        this.ocrService.uploadPdf(file, this.numParts, this.splitDir, this.outputDir, this.ocrMode).subscribe({
            next: (event) => {
                if (event.type === HttpEventType.UploadProgress) {
                    this.uploadProgress = event.total
                        ? Math.round(100 * event.loaded / event.total)
                        : 0;
                } else if (event.type === HttpEventType.Response) {
                    if (event.body) {
                        this.createdJobIds.push(event.body.id);
                    }
                    this.currentIndex++;
                    this.uploadNext();
                }
            },
            error: (err) => {
                console.error('Upload error:', file.name, err);
                this.failedFiles.push(file.name);
                this.currentIndex++;
                this.uploadNext();
            }
        });
    }

    private finishUpload() {
        this.isUploading = false;

        if (this.failedFiles.length > 0) {
            // Giữ lại các file lỗi để người dùng thử lại
            this.selectedFiles = this.selectedFiles.filter(f => this.failedFiles.includes(f.name));
            this.errorMessage = `Upload thất bại ${this.failedFiles.length} file: ${this.failedFiles.join(', ')}. Vui lòng kiểm tra kết nối server.`;
            return;
        }

        if (this.createdJobIds.length === 1) {
            this.router.navigate(['/jobs', this.createdJobIds[0]]);
        } else {
            this.router.navigate(['/jobs']);
        }
    }
}
