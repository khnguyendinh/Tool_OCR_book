import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpEventType } from '@angular/common/http';
import { OcrService } from '../../services/ocr.service';

@Component({
    selector: 'app-upload',
    standalone: true,
    imports: [CommonModule, FormsModule],
    templateUrl: './upload.component.html',
    styleUrl: './upload.component.css'
})
export class UploadComponent implements OnInit {
    selectedFile: File | null = null;
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
            this.selectFile(files[0]);
        }
    }

    onFileSelected(event: Event) {
        const input = event.target as HTMLInputElement;
        if (input.files && input.files.length > 0) {
            this.selectFile(input.files[0]);
        }
    }

    selectFile(file: File) {
        if (file.type !== 'application/pdf') {
            this.errorMessage = 'Vui lòng chọn file PDF!';
            return;
        }
        this.selectedFile = file;
        this.errorMessage = '';
    }

    removeFile() {
        this.selectedFile = null;
        this.errorMessage = '';
    }

    formatFileSize(bytes: number): string {
        if (bytes === 0) return '0 Bytes';
        const k = 1024;
        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    }

    upload() {
        if (!this.selectedFile) return;
        if (this.numParts < 1 || this.numParts > 10000) {
            this.errorMessage = 'Số phần phải từ 1 đến 10000!';
            return;
        }

        this.isUploading = true;
        this.uploadProgress = 0;
        this.errorMessage = '';

        this.ocrService.uploadPdf(this.selectedFile, this.numParts, this.splitDir, this.outputDir).subscribe({
            next: (event) => {
                if (event.type === HttpEventType.UploadProgress) {
                    this.uploadProgress = event.total
                        ? Math.round(100 * event.loaded / event.total)
                        : 0;
                } else if (event.type === HttpEventType.Response) {
                    this.isUploading = false;
                    const job = event.body;
                    if (job) {
                        this.router.navigate(['/jobs', job.id]);
                    }
                }
            },
            error: (err) => {
                this.isUploading = false;
                this.errorMessage = 'Upload thất bại! Vui lòng kiểm tra kết nối server.';
                console.error('Upload error:', err);
            }
        });
    }
}
