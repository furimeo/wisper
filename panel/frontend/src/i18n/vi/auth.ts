import type {Catalog} from '../translate'

/**
 * Đăng nhập, xác thực hai bước, hồ sơ và API token.
 *
 * <p>Khóa theo dạng `auth.<màn hình>.<thứ>`, khớp với `frontend/src/features/auth`.
 */
export const messages: Catalog = {
  'auth.profile.language.title': 'Ngôn ngữ',
  'auth.profile.language.description':
    'Áp dụng cho mọi thứ bảng điều khiển viết ra, kể cả những thông báo sinh ra lúc bạn '
    + 'không mở màn hình.',
}
